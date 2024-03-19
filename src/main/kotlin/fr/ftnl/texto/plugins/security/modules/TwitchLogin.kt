package fr.ftnl.texto.plugins.security.modules

import fr.ftnl.texto.HTTP_CLIENT
import fr.ftnl.texto.plugins.UserSession
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.sessions.*

fun AuthenticationConfig.twitchLogin(cfg: ApplicationConfig) {
    oauth("twitch_oauth") {
        skipWhen { call ->
            val session = call.sessions.get<UserSession>()
            session?.twitchUser != null
        }

        urlProvider = { cfg.property("texto.baseUrl").getString() + "/login/twitch/clb" }
        providerLookup = {
            OAuthServerSettings.OAuth2ServerSettings(
                name = "twitch",
                authorizeUrl = "https://id.twitch.tv/oauth2/authorize",
                accessTokenUrl = "https://id.twitch.tv/oauth2/token",
                requestMethod = HttpMethod.Post,
                clientId = cfg.property("security.twitchOauth.clientId").getString(),
                clientSecret = cfg.property("security.twitchOauth.clientSecret").getString(),
                defaultScopes = listOf("user:read:email"),
            )
        }
        client = HTTP_CLIENT
    }
}