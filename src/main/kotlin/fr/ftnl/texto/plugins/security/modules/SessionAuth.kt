package fr.ftnl.texto.plugins.security.modules

import fr.ftnl.texto.plugins.UserSession
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.response.*

fun AuthenticationConfig.sessionAuth() {
    session<UserSession>("auth-session") {
        validate { session -> session }
        challenge { call.respondRedirect("/login") }
    }

}