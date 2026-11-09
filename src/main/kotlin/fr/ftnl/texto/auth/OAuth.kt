package fr.ftnl.texto.auth

import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.service.LoginResult
import fr.ftnl.texto.service.OAuthProfile
import fr.ftnl.texto.service.StoredTokens
import fr.ftnl.texto.service.UserService
import fr.ftnl.texto.util.ApiException
import fr.ftnl.texto.util.Crypto
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    expectSuccess = true
}

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
) {
    fun stored() = StoredTokens(accessToken, expiresIn?.let { java.time.Instant.now().plusSeconds(it) }, refreshToken)
}

@Serializable
private data class DiscordUser(
    val id: String,
    val username: String,
    @SerialName("global_name") val globalName: String? = null,
    val avatar: String? = null,
)

@Serializable
private data class TwitchUsers(val data: List<TwitchUser>)

@Serializable
private data class TwitchUser(
    val id: String,
    val login: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("profile_image_url") val profileImageUrl: String? = null,
)

private interface Provider {
    val name: String
    fun authorizeUrl(clientId: String, redirectUri: String, state: String): String
    suspend fun fetchProfile(client: AppConfig.OAuthClient, code: String, redirectUri: String): OAuthProfile
}

private object Discord : Provider {
    override val name = "discord"
    override fun authorizeUrl(clientId: String, redirectUri: String, state: String) =
        URLBuilder("https://discord.com/oauth2/authorize").apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", clientId)
            parameters.append("scope", "identify")
            parameters.append("redirect_uri", redirectUri)
            parameters.append("state", state)
            parameters.append("prompt", "none")
        }.buildString()

    override suspend fun fetchProfile(client: AppConfig.OAuthClient, code: String, redirectUri: String): OAuthProfile {
        val token = httpClient.submitForm("https://discord.com/api/oauth2/token", parameters {
            append("client_id", client.clientId)
            append("client_secret", client.clientSecret)
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", redirectUri)
        }).body<TokenResponse>()
        val u = httpClient.get("https://discord.com/api/users/@me") { bearerAuth(token.accessToken) }.body<DiscordUser>()
        return OAuthProfile(
            provider = name,
            providerUserId = u.id,
            username = u.globalName ?: u.username,
            avatarUrl = u.avatar?.let { "https://cdn.discordapp.com/avatars/${u.id}/$it.png" },
            tokens = token.stored(),
        )
    }
}

private object Twitch : Provider {
    override val name = "twitch"
    override fun authorizeUrl(clientId: String, redirectUri: String, state: String) =
        URLBuilder("https://id.twitch.tv/oauth2/authorize").apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", clientId)
            // Aucun scope : seul l'identité publique (ID, pseudo, avatar) est lue, jamais l'email.
            parameters.append("scope", "")
            parameters.append("redirect_uri", redirectUri)
            parameters.append("state", state)
        }.buildString()

    override suspend fun fetchProfile(client: AppConfig.OAuthClient, code: String, redirectUri: String): OAuthProfile {
        val token = httpClient.submitForm("https://id.twitch.tv/oauth2/token", parameters {
            append("client_id", client.clientId)
            append("client_secret", client.clientSecret)
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", redirectUri)
        }).body<TokenResponse>()
        val u = httpClient.get("https://api.twitch.tv/helix/users") {
            bearerAuth(token.accessToken)
            header("Client-Id", client.clientId)
        }.body<TwitchUsers>().data.single()
        return OAuthProfile(name, u.id, u.displayName, u.profileImageUrl, token.stored())
    }
}

/** Fournisseurs configurés (ceux dont l'id et le secret client sont définis). */
fun AppConfig.providers(): List<String> = listOfNotNull(discord?.let { "discord" }, twitch?.let { "twitch" })

private fun safeRedirect(value: String?): String =
    value?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("/\\") }?.take(500) ?: "/"

/**
 * Routes OAuth. Les URL de callback de la v1 sont conservées (`/login/discord/clb`, `/login/twitch/clb`)
 * pour ne pas avoir à reconfigurer les applications Discord et Twitch.
 */
fun Route.oauthRoutes(cfg: AppConfig, users: UserService) {
    val log = LoggerFactory.getLogger("OAuth")
    val providers = mapOf<String, Pair<Provider, AppConfig.OAuthClient?>>(
        "discord" to (Discord to cfg.discord),
        "twitch" to (Twitch to cfg.twitch),
    )

    get("/login/{provider}") {
        val name = call.parameters["provider"]!!
        val (provider, client) = providers[name] ?: return@get call.respond(HttpStatusCode.NotFound)
        if (client == null) return@get call.respondRedirect("/login?error=provider_disabled")
        val link = call.request.queryParameters["link"] == "1"
        if (link && call.sessions.get<UserSession>() == null) return@get call.respondRedirect("/login")
        val state = Crypto.randomKey(32)
        call.sessions.set(OAuthState(state, name, link, safeRedirect(call.request.queryParameters["redirect"])))
        call.respondRedirect(provider.authorizeUrl(client.clientId, "${cfg.baseUrl}/login/$name/clb", state))
    }

    get("/login/{provider}/clb") {
        val name = call.parameters["provider"]!!
        val (provider, client) = providers[name] ?: return@get call.respond(HttpStatusCode.NotFound)
        client ?: return@get call.respond(HttpStatusCode.NotFound)
        val saved = call.sessions.get<OAuthState>()
        call.sessions.clear<OAuthState>()
        val code = call.request.queryParameters["code"]
        if (saved == null || saved.provider != name || saved.state != call.request.queryParameters["state"] || code == null) {
            return@get call.respondRedirect("/login?error=oauth_state")
        }
        val profile = try {
            provider.fetchProfile(client, code, "${cfg.baseUrl}/login/$name/clb")
        } catch (e: Exception) {
            log.warn("Échec OAuth $name", e)
            return@get call.respondRedirect("/login?error=oauth_failed")
        }
        val current = if (saved.link) call.sessions.get<UserSession>()?.userId else null
        val result = try {
            users.loginOrLink(profile, current)
        } catch (e: ApiException) {
            return@get call.respondRedirect("/settings?error=${e.code}")
        }
        when (result) {
            LoginResult.Banned -> call.respondRedirect("/login?error=banned")
            LoginResult.RegistrationClosed -> call.respondRedirect("/login?error=registration_closed")
            is LoginResult.Ok -> {
                call.sessions.set(UserSession(result.userId, System.currentTimeMillis()))
                val target = if (saved.link) "/settings?linked=$name${if (result.merged) "&merged=1" else ""}"
                else saved.redirect + (if (result.claimedLegacy) (if ('?' in saved.redirect) "&" else "?") + "claimed=1" else "")
                call.respondRedirect(target)
            }
        }
    }

    // Connexion factice pour le développement local (jamais active en production).
    if (cfg.devLogin) {
        log.warn("TEXTO_DEV_LOGIN actif : /login/dev permet de se connecter sans OAuth. Ne pas utiliser en production.")
        get("/login/dev") {
            val name = call.request.queryParameters["name"]?.take(40) ?: "dev"
            val profile = OAuthProfile("dev", name.lowercase(), name, null)
            val current = call.sessions.get<UserSession>()?.userId?.takeIf { call.request.queryParameters["link"] == "1" }
            when (val r = users.loginOrLink(profile, current)) {
                is LoginResult.Ok -> {
                    call.sessions.set(UserSession(r.userId, System.currentTimeMillis()))
                    call.respondRedirect(safeRedirect(call.request.queryParameters["redirect"]))
                }
                else -> call.respondRedirect("/login?error=banned")
            }
        }
    }

    post("/api/auth/logout") {
        call.sessions.clear<UserSession>()
        call.respond(HttpStatusCode.NoContent)
    }
}
