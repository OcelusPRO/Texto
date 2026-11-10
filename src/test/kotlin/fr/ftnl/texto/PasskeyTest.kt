package fr.ftnl.texto

import fr.ftnl.texto.auth.PasskeyLoginInput
import fr.ftnl.texto.auth.PasskeyRegisterInput
import fr.ftnl.texto.auth.PasskeyService
import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.db.Db
import fr.ftnl.texto.db.Users
import fr.ftnl.texto.db.dbBlocking
import fr.ftnl.texto.service.LoginResult
import fr.ftnl.texto.service.OAuthProfile
import fr.ftnl.texto.service.UserService
import fr.ftnl.texto.util.ApiException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.*
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Authentificateur logiciel minimal (ES256, attestation « none ») pour tester les cérémonies WebAuthn de bout en bout. */
private class SoftAuthenticator(private val rpId: String, private val origin: String) {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val credentialId = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
    lateinit var userHandle: String
    private var counter = 0

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun unsigned32(x: java.math.BigInteger): ByteArray {
        val raw = x.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    /** Encodage CBOR minimal : entiers, chaînes, octets et maps. */
    private fun cbor(v: Any): ByteArray {
        val out = ByteArrayOutputStream()
        fun head(major: Int, n: Long) {
            val m = major shl 5
            when {
                n < 24 -> out.write(m or n.toInt())
                n < 256 -> { out.write(m or 24); out.write(n.toInt()) }
                else -> { out.write(m or 25); out.write((n shr 8).toInt()); out.write(n.toInt() and 0xff) }
            }
        }
        fun enc(x: Any) {
            when (x) {
                is Int -> if (x >= 0) head(0, x.toLong()) else head(1, (-1 - x).toLong())
                is String -> { val b = x.toByteArray(); head(3, b.size.toLong()); out.write(b) }
                is ByteArray -> { head(2, x.size.toLong()); out.write(x) }
                is Map<*, *> -> { head(5, x.size.toLong()); x.forEach { (k, value) -> enc(k!!); enc(value!!) } }
                else -> error("type CBOR non géré")
            }
        }
        enc(v)
        return out.toByteArray()
    }

    private fun coseKey(): ByteArray {
        val pub = keys.public as ECPublicKey
        // kty EC2, alg ES256, crv P-256, x, y
        return cbor(linkedMapOf(1 to 2, 3 to -7, -1 to 1, -2 to unsigned32(pub.w.affineX), -3 to unsigned32(pub.w.affineY)))
    }

    private fun authData(flags: Int, attested: Boolean): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(sha256(rpId.toByteArray()))
        buf.write(flags)
        buf.write(ByteBuffer.allocate(4).putInt(counter).array())
        if (attested) {
            buf.write(ByteArray(16))
            buf.write(byteArrayOf((credentialId.size shr 8).toByte(), credentialId.size.toByte()))
            buf.write(credentialId)
            buf.write(coseKey())
        }
        return buf.toByteArray()
    }

    private fun clientData(type: String, challenge: String) =
        """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}""".toByteArray()

    fun create(options: JsonObject): JsonElement {
        userHandle = options["user"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val challenge = options["challenge"]!!.jsonPrimitive.content
        val ad = authData(0x45, attested = true) // UP | UV | AT
        val att = cbor(linkedMapOf("fmt" to "none", "attStmt" to emptyMap<String, Any>(), "authData" to ad))
        return buildJsonObject {
            put("id", b64.encodeToString(credentialId)); put("rawId", b64.encodeToString(credentialId)); put("type", "public-key")
            putJsonObject("clientExtensionResults") {}
            putJsonObject("response") {
                put("clientDataJSON", b64.encodeToString(clientData("webauthn.create", challenge)))
                put("attestationObject", b64.encodeToString(att))
                putJsonArray("transports") { add("internal") }
            }
        }
    }

    fun get(options: JsonObject, origin: String = this.origin): JsonElement {
        counter++
        val challenge = options["challenge"]!!.jsonPrimitive.content
        val ad = authData(0x05, attested = false) // UP | UV
        val cd = """{"type":"webauthn.get","challenge":"$challenge","origin":"$origin","crossOrigin":false}""".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(ad + sha256(cd)) }.sign()
        return buildJsonObject {
            put("id", b64.encodeToString(credentialId)); put("rawId", b64.encodeToString(credentialId)); put("type", "public-key")
            putJsonObject("clientExtensionResults") {}
            putJsonObject("response") {
                put("clientDataJSON", b64.encodeToString(cd))
                put("authenticatorData", b64.encodeToString(ad))
                put("signature", b64.encodeToString(sig))
                put("userHandle", userHandle)
            }
        }
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasskeyTest {
    private val pg = PostgreSQLContainer("postgres:17-alpine")
    private lateinit var cfg: AppConfig

    @BeforeAll
    fun setup() {
        pg.start()
        val ds = PGSimpleDataSource().apply { setURL(pg.jdbcUrl); user = pg.username; password = pg.password }
        cfg = AppConfig.fromEnv().copy(
            db = AppConfig.DbConfig(pg.jdbcUrl, pg.username, pg.password, 4), dev = false, baseUrl = "https://texto.test",
        )
        Db.init(ds)
    }

    @AfterAll
    fun teardown() = pg.stop()

    private fun code(block: suspend () -> Unit) = runBlocking { assertFailsWith<ApiException> { block() }.code }

    @Test
    fun `register a passkey then sign in with it`() = runBlocking {
        val users = UserService(cfg)
        val uid = (users.loginOrLink(OAuthProfile("dev", "pk-alice", "Alice", null), null) as LoginResult.Ok).userId
        val svc = PasskeyService(cfg)
        val auth = SoftAuthenticator("texto.test", "https://texto.test")

        val reg = svc.startRegistration(uid)
        val created = svc.finishRegistration(uid, PasskeyRegisterInput(reg.requestId, auth.create(reg.publicKey.jsonObject), "Laptop"))
        assertEquals("Laptop", created.name)
        assertEquals(listOf("Laptop"), svc.list(uid).map { it.name })

        // Une cérémonie ne peut servir qu'une fois.
        assertEquals("passkey_expired", code { svc.finishRegistration(uid, PasskeyRegisterInput(reg.requestId, auth.create(reg.publicKey.jsonObject))) })

        val login = svc.startLogin()
        assertEquals(uid, svc.finishLogin(PasskeyLoginInput(login.requestId, auth.get(login.publicKey.jsonObject))))
        assertEquals(1, svc.list(uid).count { it.lastUsedAt != null })

        // Mauvaise origine (site d'hameçonnage) : refusée.
        val phish = svc.startLogin()
        assertEquals("passkey_unknown", code { svc.finishLogin(PasskeyLoginInput(phish.requestId, auth.get(phish.publicKey.jsonObject, "https://evil.test"))) })

        // Compte banni : connexion refusée.
        dbBlocking { Users.update({ Users.id eq uid }) { it[banned] = true } }
        val again = svc.startLogin()
        assertEquals("banned", code { svc.finishLogin(PasskeyLoginInput(again.requestId, auth.get(again.publicKey.jsonObject))) })
        dbBlocking { Users.update({ Users.id eq uid }) { it[banned] = false } }

        // Passkey supprimée : elle ne connecte plus.
        svc.delete(uid, created.id)
        val gone = svc.startLogin()
        assertEquals("passkey_unknown", code { svc.finishLogin(PasskeyLoginInput(gone.requestId, auth.get(gone.publicKey.jsonObject))) })
    }

    @Test
    fun `passkeys of another user cannot be renamed or deleted`() = runBlocking {
        val users = UserService(cfg)
        val a = (users.loginOrLink(OAuthProfile("dev", "pk-a", "A", null), null) as LoginResult.Ok).userId
        val b = (users.loginOrLink(OAuthProfile("dev", "pk-b", "B", null), null) as LoginResult.Ok).userId
        val svc = PasskeyService(cfg)
        val auth = SoftAuthenticator("texto.test", "https://texto.test")
        val reg = svc.startRegistration(a)
        val pk = svc.finishRegistration(a, PasskeyRegisterInput(reg.requestId, auth.create(reg.publicKey.jsonObject)))
        assertEquals("Passkey", pk.name)
        assertEquals("not_found", code { svc.delete(b, pk.id) })
        assertEquals("not_found", code { svc.rename(b, pk.id, "x") })
        // La cérémonie d'un compte ne peut pas être terminée par un autre.
        val reg2 = svc.startRegistration(a)
        assertEquals("passkey_expired", code { svc.finishRegistration(b, PasskeyRegisterInput(reg2.requestId, auth.create(reg2.publicKey.jsonObject))) })
    }
}
