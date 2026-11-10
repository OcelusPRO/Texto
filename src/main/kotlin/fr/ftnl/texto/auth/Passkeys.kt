package fr.ftnl.texto.auth

import com.yubico.webauthn.*
import com.yubico.webauthn.data.*
import com.yubico.webauthn.exception.AssertionFailedException
import com.yubico.webauthn.exception.RegistrationFailedException
import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.db.Passkeys
import fr.ftnl.texto.db.Users
import fr.ftnl.texto.db.db
import fr.ftnl.texto.db.dbBlocking
import fr.ftnl.texto.util.Crypto
import fr.ftnl.texto.util.badRequest
import fr.ftnl.texto.util.forbidden
import fr.ftnl.texto.util.notFound
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.SecureRandom
import java.time.Instant
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import com.yubico.webauthn.data.ByteArray as Bytes

@Serializable
data class PasskeyDto(val id: Long, val name: String, val createdAt: Long, val lastUsedAt: Long?)

/** Options à passer à `navigator.credentials.create/get` (format JSON WebAuthn), et l'identifiant de la cérémonie. */
@Serializable
data class PasskeyOptionsDto(val requestId: String, val publicKey: JsonElement)

@Serializable
data class PasskeyRegisterInput(val requestId: String, val credential: JsonElement, val name: String = "")

@Serializable
data class PasskeyLoginInput(val requestId: String, val credential: JsonElement)

@Serializable
data class PasskeyRenameInput(val name: String)

/**
 * Passkeys (WebAuthn). Une passkey s'ajoute depuis les paramètres d'un compte déjà connecté (via Discord / Twitch),
 * puis permet de s'y connecter sans fournisseur OAuth. Les passkeys sont « découvrables » : pas besoin de saisir d'identifiant.
 */
class PasskeyService(cfg: AppConfig) {
    private val log = LoggerFactory.getLogger("Passkeys")
    private val random = SecureRandom()

    /** Cérémonies en cours (défi WebAuthn), gardées 5 minutes en mémoire. */
    private class Pending(val json: String, val userId: Long?, val expiresAt: Long)
    private val pending = ConcurrentHashMap<String, Pending>()

    private val repository = object : CredentialRepository {
        override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> = dbBlocking {
            val uid = username.toLongOrNull() ?: return@dbBlocking emptySet()
            Passkeys.selectAll().where { Passkeys.user eq uid }.map {
                PublicKeyCredentialDescriptor.builder().id(Bytes(it[Passkeys.credentialId])).transports(parseTransports(it[Passkeys.transports])).build()
            }.toSet()
        }

        override fun getUserHandleForUsername(username: String): Optional<Bytes> = dbBlocking {
            val uid = username.toLongOrNull() ?: return@dbBlocking Optional.empty()
            Optional.ofNullable(handleOf(uid)?.let(::Bytes))
        }

        override fun getUsernameForUserHandle(userHandle: Bytes): Optional<String> = dbBlocking {
            Optional.ofNullable(
                Passkeys.select(Passkeys.user).where { Passkeys.userHandle eq userHandle.bytes }.limit(1).singleOrNull()
                    ?.get(Passkeys.user)?.value?.toString()
            )
        }

        override fun lookup(credentialId: Bytes, userHandle: Bytes): Optional<RegisteredCredential> = dbBlocking {
            Optional.ofNullable(
                Passkeys.selectAll().where { (Passkeys.credentialId eq credentialId.bytes) and (Passkeys.userHandle eq userHandle.bytes) }
                    .singleOrNull()?.let(::toRegistered)
            )
        }

        override fun lookupAll(credentialId: Bytes): Set<RegisteredCredential> = dbBlocking {
            Passkeys.selectAll().where { Passkeys.credentialId eq credentialId.bytes }.map(::toRegistered).toSet()
        }
    }

    private val rp: RelyingParty = run {
        val base = URI(cfg.baseUrl)
        val origin = "${base.scheme}://${base.authority}"
        RelyingParty.builder()
            .identity(RelyingPartyIdentity.builder().id(base.host).name(cfg.siteName).build())
            .credentialRepository(repository)
            .origins(setOf(origin))
            // En développement, le frontend Vite (port 5173) relaie l'API : on accepte tout port du même hôte.
            .allowOriginPort(cfg.dev || base.host == "localhost")
            .build()
    }

    private fun toRegistered(row: ResultRow): RegisteredCredential = RegisteredCredential.builder()
        .credentialId(Bytes(row[Passkeys.credentialId]))
        .userHandle(Bytes(row[Passkeys.userHandle]))
        .publicKeyCose(Bytes(row[Passkeys.publicKeyCose]))
        .signatureCount(row[Passkeys.signCount])
        .build()

    private fun parseTransports(s: String): Set<AuthenticatorTransport> =
        s.split(',').filter(String::isNotBlank).map { AuthenticatorTransport.of(it) }.toSet()

    /** userHandle déjà attribué au compte (première passkey), sinon null. À appeler dans une transaction. */
    private fun handleOf(userId: Long): kotlin.ByteArray? =
        Passkeys.select(Passkeys.userHandle).where { Passkeys.user eq userId }.limit(1).singleOrNull()?.get(Passkeys.userHandle)

    private fun remember(json: String, userId: Long?): String {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { it.value.expiresAt < now }
        if (pending.size > 10_000) badRequest("too_many")
        val id = Crypto.randomKey(32)
        pending[id] = Pending(json, userId, now + 5 * 60_000)
        return id
    }

    private fun take(requestId: String, userId: Long?): String {
        val p = pending.remove(requestId) ?: badRequest("passkey_expired")
        if (p.expiresAt < System.currentTimeMillis() || p.userId != userId) badRequest("passkey_expired")
        return p.json
    }

    private fun publicKeyOf(credentialsJson: String): JsonElement =
        Json.parseToJsonElement(credentialsJson).jsonObject["publicKey"] ?: JsonObject(emptyMap())

    // ---------------------------------------------------------------- gestion depuis le compte

    fun list(userId: Long): List<PasskeyDto> = dbBlocking {
        Passkeys.selectAll().where { Passkeys.user eq userId }.orderBy(Passkeys.createdAt, SortOrder.ASC).map {
            PasskeyDto(it[Passkeys.id].value, it[Passkeys.name], it[Passkeys.createdAt].toEpochMilli(), it[Passkeys.lastUsedAt]?.toEpochMilli())
        }
    }

    suspend fun startRegistration(userId: Long): PasskeyOptionsDto {
        val (user, handle) = db {
            if (Passkeys.selectAll().where { Passkeys.user eq userId }.count() >= 20) badRequest("too_many")
            val u = Users.selectAll().where { Users.id eq userId }.singleOrNull() ?: notFound()
            u to (handleOf(userId) ?: kotlin.ByteArray(32).also(random::nextBytes))
        }
        val options = rp.startRegistration(
            StartRegistrationOptions.builder()
                .user(
                    UserIdentity.builder()
                        .name(userId.toString())
                        .displayName("${user[Users.displayName]} (@${user[Users.handle]})")
                        .id(Bytes(handle))
                        .build()
                )
                .authenticatorSelection(
                    AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.PREFERRED)
                        .build()
                )
                .timeout(5 * 60_000L)
                .build()
        )
        return PasskeyOptionsDto(remember(options.toJson(), userId), publicKeyOf(options.toCredentialsCreateJson()))
    }

    suspend fun finishRegistration(userId: Long, input: PasskeyRegisterInput): PasskeyDto {
        val request = PublicKeyCredentialCreationOptions.fromJson(take(input.requestId, userId))
        val response = runCatching { PublicKeyCredential.parseRegistrationResponseJson(input.credential.toString()) }
            .getOrElse { badRequest("passkey_invalid") }
        val result = try {
            rp.finishRegistration(FinishRegistrationOptions.builder().request(request).response(response).build())
        } catch (e: RegistrationFailedException) {
            log.info("Enregistrement de passkey refusé : {}", e.message)
            badRequest("passkey_invalid")
        }
        val credentialId = result.keyId.id.bytes
        val transports = result.keyId.transports.map { t -> t.joinToString(",") { it.id } }.orElse("")
        val id = db {
            if (Passkeys.selectAll().where { Passkeys.credentialId eq credentialId }.any()) badRequest("passkey_exists")
            Passkeys.insertAndGetId {
                it[user] = userId
                it[Passkeys.credentialId] = credentialId
                it[userHandle] = request.user.id.bytes
                it[publicKeyCose] = result.publicKeyCose.bytes
                it[signCount] = result.signatureCount
                it[name] = input.name.trim().take(100).ifEmpty { "Passkey" }
                it[Passkeys.transports] = transports
                it[createdAt] = Instant.now()
            }.value
        }
        return list(userId).first { it.id == id }
    }

    fun rename(userId: Long, id: Long, name: String) = dbBlocking {
        val n = name.trim().take(100).ifEmpty { badRequest("name_required") }
        if (Passkeys.update({ (Passkeys.id eq id) and (Passkeys.user eq userId) }) { it[Passkeys.name] = n } == 0) notFound()
    }

    fun delete(userId: Long, id: Long) = dbBlocking {
        if (Passkeys.deleteWhere { (Passkeys.id eq id) and (Passkeys.user eq userId) } == 0) notFound()
    }

    // ---------------------------------------------------------------- connexion

    fun startLogin(): PasskeyOptionsDto {
        val request = rp.startAssertion(
            StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.PREFERRED)
                .timeout(5 * 60_000L)
                .build()
        )
        return PasskeyOptionsDto(remember(request.toJson(), null), publicKeyOf(request.toCredentialsGetJson()))
    }

    /** Vérifie la signature de l'authentificateur et renvoie l'utilisateur correspondant. */
    suspend fun finishLogin(input: PasskeyLoginInput): Long {
        val request = AssertionRequest.fromJson(take(input.requestId, null))
        val response = runCatching { PublicKeyCredential.parseAssertionResponseJson(input.credential.toString()) }
            .getOrElse { badRequest("passkey_invalid") }
        val result = try {
            rp.finishAssertion(FinishAssertionOptions.builder().request(request).response(response).build())
        } catch (e: AssertionFailedException) {
            log.info("Connexion par passkey refusée : {}", e.message)
            badRequest("passkey_unknown")
        }
        if (!result.isSuccess) badRequest("passkey_unknown")
        val credentialId = result.credential.credentialId.bytes
        return db {
            val row = Passkeys.selectAll().where { Passkeys.credentialId eq credentialId }.singleOrNull() ?: badRequest("passkey_unknown")
            val uid = row[Passkeys.user].value
            val now = Instant.now()
            Passkeys.update({ Passkeys.id eq row[Passkeys.id] }) {
                it[signCount] = result.signatureCount
                it[lastUsedAt] = now
            }
            val banned = Users.select(Users.banned).where { Users.id eq uid }.single()[Users.banned]
            if (banned) forbidden("banned")
            Users.update({ Users.id eq uid }) { it[lastLoginAt] = now }
            uid
        }
    }
}

fun Route.passkeyRoutes(passkeys: PasskeyService) {
    route("/api/me/passkeys") {
        get { call.respond(passkeys.list(call.requireUser(allowToken = false, allowImpersonation = false).userId)) }
        post("/options") {
            call.respond(passkeys.startRegistration(call.requireUser(allowToken = false, allowImpersonation = false).userId))
        }
        post {
            val id = call.requireUser(allowToken = false, allowImpersonation = false)
            call.respond(HttpStatusCode.Created, passkeys.finishRegistration(id.userId, call.receive()))
        }
        patch("/{id}") {
            val id = call.requireUser(allowToken = false, allowImpersonation = false)
            passkeys.rename(id.userId, call.parameters["id"]?.toLongOrNull() ?: notFound(), call.receive<PasskeyRenameInput>().name)
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/{id}") {
            val id = call.requireUser(allowToken = false, allowImpersonation = false)
            passkeys.delete(id.userId, call.parameters["id"]?.toLongOrNull() ?: notFound())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/api/auth/passkey") {
        post("/options") { call.respond(passkeys.startLogin()) }
        post {
            val userId = passkeys.finishLogin(call.receive())
            call.sessions.set(UserSession(userId, System.currentTimeMillis()))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
