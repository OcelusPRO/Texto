package fr.ftnl.texto.plugins.security

import fr.ftnl.texto.plugins.security.modules.*
import io.ktor.server.application.*
import io.ktor.server.auth.*

fun Application.configureSecurity() {
    install(Authentication) {
        apiSecurityConfig()
        discordLogin(this@configureSecurity.environment.config)
        twitchLogin(this@configureSecurity.environment.config)
        formAuth(this@configureSecurity.environment.config)
        sessionAuth()
    }
}

