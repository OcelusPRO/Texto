package fr.ftnl.texto.auth

import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.service.TokenService
import fr.ftnl.texto.service.UserService
import fr.ftnl.texto.service.Viewer
import fr.ftnl.texto.util.forbidden
import fr.ftnl.texto.util.unauthorized
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class UserSession(val userId: Long, val issuedAt: Long, val impersonatorId: Long? = null)

/** Durée maximale d'une session « agir en tant que » : ensuite on revient automatiquement au compte administrateur. */
const val IMPERSONATION_MAX_MS = 8L * 60 * 60 * 1000

@Serializable
data class OAuthState(val state: String, val provider: String, val link: Boolean, val redirect: String)

/** Clés des posts déjà vus par ce navigateur (pour ne compter qu'une vue par visiteur). */
@Serializable
data class ViewSession(val keys: List<String> = emptyList())

fun Application.installSessions(cfg: AppConfig) {
    val secure = cfg.baseUrl.startsWith("https://")
    val encrypt = hex(cfg.sessionEncryptKey)
    val sign = hex(cfg.sessionSignKey)
    install(Sessions) {
        cookie<UserSession>("texto_session") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = secure
            cookie.maxAgeInSeconds = 60L * 60 * 24 * 30
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerEncrypt(encrypt, sign))
        }
        cookie<OAuthState>("texto_oauth") {
            cookie.path = "/login"
            cookie.httpOnly = true
            cookie.secure = secure
            cookie.maxAgeInSeconds = 600
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerEncrypt(encrypt, sign))
        }
        cookie<ViewSession>("texto_views") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = secure
            cookie.maxAgeInSeconds = 60L * 60 * 24 * 30
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerMessageAuthentication(sign))
        }
    }
}

/** Identité de l'appelant : session navigateur, ou token API (en-tête Authorization: Bearer / X-API-Key). */
data class CallIdentity(val userId: Long, val viaToken: Boolean, val scopes: Set<String>, val isAdmin: Boolean, val impersonatorId: Long? = null)

class AuthResolver(private val users: UserService) {
    fun resolve(call: ApplicationCall): CallIdentity? {
        val bearer = call.request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        val apiKey = bearer ?: call.request.header("X-API-Key")?.trim()
        if (!apiKey.isNullOrEmpty()) {
            val p = TokenService.authenticate(apiKey) ?: return null
            return CallIdentity(p.userId, true, p.scopes, false, null)
        }
        var session = call.sessions.get<UserSession>() ?: return null
        val impersonator = session.impersonatorId
        if (impersonator != null) {
            // L'administrateur doit rester admin et actif, et la session ne doit pas être trop ancienne : sinon retour à son compte.
            val adminOk = users.role(impersonator) == "admin" && users.isActive(impersonator)
            val expired = System.currentTimeMillis() - session.issuedAt > IMPERSONATION_MAX_MS
            if (!adminOk || expired) {
                if (!adminOk) { call.sessions.clear<UserSession>(); return null }
                session = UserSession(impersonator, System.currentTimeMillis())
                call.sessions.set(session)
            }
        }
        val role = users.role(session.userId)
        if (role == null || !users.isActive(session.userId)) {
            call.sessions.clear<UserSession>()
            return null
        }
        return CallIdentity(session.userId, false, setOf("*"), role == "admin", session.impersonatorId)
    }
}

private class IdentityHolder(val value: CallIdentity?)
private val IdentityKey = AttributeKey<IdentityHolder>("texto.identity")
lateinit var authResolver: AuthResolver

fun ApplicationCall.identity(): CallIdentity? {
    attributes.getOrNull(IdentityKey)?.let { return it.value }
    val id = authResolver.resolve(this)
    attributes.put(IdentityKey, IdentityHolder(id))
    return id
}

fun ApplicationCall.viewer(): Viewer = identity()?.let { Viewer(it.userId, it.isAdmin) } ?: Viewer(null)

/** Exige un utilisateur connecté. Les tokens API doivent porter [scope] ; certaines routes leur sont interdites. */
fun ApplicationCall.requireUser(scope: String? = null, allowToken: Boolean = true, allowImpersonation: Boolean = true): CallIdentity {
    val id = identity() ?: unauthorized()
    // Pendant « agir en tant que », les actions sur le compte lui-même (suppression, jetons, comptes liés, identité) sont interdites.
    if (!allowImpersonation && id.impersonatorId != null) forbidden("impersonating")
    if (id.viaToken) {
        if (!allowToken) forbidden("session_required")
        if (scope != null && scope !in id.scopes) forbidden("missing_scope")
    }
    return id
}

/** Identifiant de l'administrateur réel : l'admin d'origine pendant « agir en tant que », sinon l'admin connecté. */
fun ApplicationCall.requireRealAdmin(): Long {
    val id = identity() ?: unauthorized()
    if (id.viaToken) forbidden("session_required")
    id.impersonatorId?.let { return it }
    if (!id.isAdmin) forbidden()
    return id.userId
}

fun ApplicationCall.requireAdmin(): CallIdentity {
    val id = requireUser(allowToken = false)
    if (!id.isAdmin) forbidden()
    return id
}

/** Protection CSRF : les requêtes modifiantes authentifiées par cookie doivent venir de notre origine. */
fun ApplicationCall.checkOrigin(cfg: AppConfig): Boolean {
    if (request.httpMethod in setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)) return true
    if (request.header(HttpHeaders.Authorization) != null || request.header("X-API-Key") != null) return true
    val origin = request.header(HttpHeaders.Origin) ?: return true
    if (cfg.dev) return true
    val expected = URI(cfg.baseUrl)
    val actual = runCatching { URI(origin) }.getOrNull() ?: return false
    return actual.scheme == expected.scheme && actual.host == expected.host && actual.port == expected.port
}
