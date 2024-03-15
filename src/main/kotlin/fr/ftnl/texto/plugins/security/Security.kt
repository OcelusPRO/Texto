package fr.ftnl.texto.plugins.security

import fr.ftnl.texto.plugins.security.modules.apiSecurityConfig
import fr.ftnl.texto.plugins.security.modules.discordLogin
import io.ktor.server.application.*
import io.ktor.server.auth.*

fun Application.configureSecurity() {
    install(Authentication) {
        apiSecurityConfig()
        discordLogin(this@configureSecurity.environment.config)
    }
}

