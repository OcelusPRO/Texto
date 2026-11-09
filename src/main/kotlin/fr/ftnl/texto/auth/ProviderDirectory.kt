package fr.ftnl.texto.auth

import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.service.StoredTokens
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant

/** Pseudo/avatar actuels d'un compte lié ; [tokens] : jetons renouvelés pendant l'appel, à enregistrer. */
data class ProviderProfile(val username: String, val avatarUrl: String?, val tokens: StoredTokens? = null)

/** Interroge Discord / Twitch pour retrouver le pseudo et l'avatar actuels d'un compte lié. */
interface ProviderDirectory {
    suspend fun fetch(provider: String, providerUserId: String, tokens: StoredTokens?): ProviderProfile?
}

@Serializable
internal data class RefreshedToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

@Serializable
private data class DiscordUserLite(val id: String, val username: String, @SerialName("global_name") val globalName: String? = null, val avatar: String? = null)

@Serializable
private data class TwitchUsersLite(val data: List<TwitchUserLite>)

@Serializable
private data class TwitchUserLite(val id: String, @SerialName("display_name") val displayName: String, @SerialName("profile_image_url") val profileImageUrl: String? = null)

@Serializable
private data class AppToken(@SerialName("access_token") val accessToken: String, @SerialName("expires_in") val expiresIn: Long = 3600)

/**
 * Utilise les jetons OAuth enregistrés à la connexion de l'utilisateur : jeton d'accès s'il est encore valable,
 * sinon renouvellement par le jeton de rafraîchissement. Aucun bot n'est nécessaire.
 * Twitch a en plus un repli sans jeton (jeton d'application), utile pour les comptes importés de la v1.
 */
class HttpProviderDirectory(private val cfg: AppConfig) : ProviderDirectory {
    private val log = LoggerFactory.getLogger(HttpProviderDirectory::class.java)
    private var twitchAppToken: Pair<String, Long>? = null

    override suspend fun fetch(provider: String, providerUserId: String, tokens: StoredTokens?): ProviderProfile? = try {
        when (provider) {
            "discord" -> discord(tokens)
            "twitch" -> twitch(providerUserId, tokens)
            else -> null
        }
    } catch (e: Exception) {
        log.warn("Rafraîchissement $provider/$providerUserId impossible : ${e.message}")
        null
    }

    /** Jeton d'accès utilisable (et jetons éventuellement renouvelés), ou null. */
    private suspend fun usable(tokens: StoredTokens?, refresh: suspend (String) -> RefreshedToken): Pair<String, StoredTokens?>? {
        tokens ?: return null
        if (tokens.accessToken != null && tokens.expiresAt != null && tokens.expiresAt.isAfter(Instant.now().plusSeconds(60))) {
            return tokens.accessToken to null
        }
        val rt = tokens.refreshToken ?: return null
        val fresh = refresh(rt)
        val renewed = StoredTokens(fresh.accessToken, fresh.expiresIn?.let { Instant.now().plusSeconds(it) }, fresh.refreshToken ?: rt)
        return fresh.accessToken to renewed
    }

    private suspend fun discord(tokens: StoredTokens?): ProviderProfile? {
        val client = cfg.discord ?: return null
        val (access, renewed) = usable(tokens) { rt ->
            httpClient.submitForm("https://discord.com/api/v10/oauth2/token", parameters {
                append("client_id", client.clientId)
                append("client_secret", client.clientSecret)
                append("grant_type", "refresh_token")
                append("refresh_token", rt)
            }).body<RefreshedToken>()
        } ?: return null
        val u = httpClient.get("https://discord.com/api/v10/users/@me") { bearerAuth(access) }.body<DiscordUserLite>()
        return ProviderProfile(u.globalName ?: u.username, u.avatar?.let { "https://cdn.discordapp.com/avatars/${u.id}/$it.png" }, renewed)
    }

    private suspend fun twitch(id: String, tokens: StoredTokens?): ProviderProfile? {
        val client = cfg.twitch ?: return null
        val user = usable(tokens) { rt ->
            httpClient.submitForm("https://id.twitch.tv/oauth2/token", parameters {
                append("client_id", client.clientId)
                append("client_secret", client.clientSecret)
                append("grant_type", "refresh_token")
                append("refresh_token", rt)
            }).body<RefreshedToken>()
        }
        // Avec un jeton utilisateur : /users sans paramètre = l'utilisateur courant ; sinon, jeton d'application + id.
        val (bearer, query) = if (user != null) user.first to "" else appToken(client) to "?id=$id"
        val u = httpClient.get("https://api.twitch.tv/helix/users$query") {
            bearerAuth(bearer)
            header("Client-Id", client.clientId)
        }.body<TwitchUsersLite>().data.firstOrNull() ?: return null
        return ProviderProfile(u.displayName, u.profileImageUrl, user?.second)
    }

    private suspend fun appToken(client: AppConfig.OAuthClient): String {
        twitchAppToken?.takeIf { it.second > System.currentTimeMillis() + 60_000 }?.let { return it.first }
        val t = httpClient.submitForm("https://id.twitch.tv/oauth2/token", parameters {
            append("client_id", client.clientId)
            append("client_secret", client.clientSecret)
            append("grant_type", "client_credentials")
        }).body<AppToken>()
        twitchAppToken = t.accessToken to (System.currentTimeMillis() + t.expiresIn * 1000)
        return t.accessToken
    }
}
