package fr.ftnl.texto.plugins.security.modules

import fr.ftnl.texto.HTTP_CLIENT
import fr.ftnl.texto.plugins.UserSession
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.sessions.*

fun AuthenticationConfig.discordLogin(cfg: ApplicationConfig) {
    oauth("discord_oauth") {
        skipWhen { call ->
            val session = call.sessions.get<UserSession>()
            session?.discordUser != null
        }

        urlProvider = { cfg.property("texto.baseUrl").getString() + "/clb" }
        providerLookup = {
            OAuthServerSettings.OAuth2ServerSettings(
                name = "discord",
                authorizeUrl = "https://discord.com/oauth2/authorize",
                accessTokenUrl = "https://discord.com/api/oauth2/token",
                requestMethod = HttpMethod.Post,
                clientId = cfg.property("security.discordOauth.clientId").getString(),
                clientSecret = cfg.property("security.discordOauth.clientSecret").getString(),
                defaultScopes = listOf("identify", "connections", "email"),
            )
        }
        client = HTTP_CLIENT
    }
}