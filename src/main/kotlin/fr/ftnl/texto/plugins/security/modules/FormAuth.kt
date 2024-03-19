package fr.ftnl.texto.plugins.security.modules

import fr.ftnl.texto.database.models.UserLogin
import fr.ftnl.texto.plugins.UserSession
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder


fun AuthenticationConfig.formAuth(cfg: ApplicationConfig) {
    form("auth-form") {
        userParamName = "identifier"
        passwordParamName = "password"

        validate { credentials ->
            val user = UserLogin.get(credentials.name) ?: return@validate null
            val valid = verifyArgon2Password(credentials.password, user.password, getArgon2Config(cfg))
            if (valid.not()) return@validate null
            UserSession(null, null, user.emailHash)
        }
        challenge {
            call.respond(HttpStatusCode.Unauthorized, "Credentials are not valid")
        }

        skipWhen { call ->
            val session = call.sessions.get<UserSession>()
            session?.connectedEmailHash != null
        }
    }
}


data class Argon2Conf(
    val saltLength: Int = 16,
    val hashLength: Int = 512,
    val parallelism: Int = 1,
    val memory: Int = 60000,
    val iterator: Int = 10,
)
private fun verifyArgon2Password(password: String, hash: String, conf: Argon2Conf): Boolean {
    val arg2SpringSecurity = Argon2PasswordEncoder(
        conf.saltLength,
        conf.hashLength,
        conf.parallelism,
        conf.memory,
        conf.iterator
    )
    return arg2SpringSecurity.matches(password, hash)
}

fun getArgon2Config(cfg: ApplicationConfig) = Argon2Conf(
    cfg.property("security.security.saltLength").getString().toInt(),
    cfg.property("security.security.hashLength").getString().toInt(),
    cfg.property("security.security.parallelism").getString().toInt(),
    cfg.property("security.security.memory").getString().toInt(),
    cfg.property("security.security.iterator").getString().toInt()
)