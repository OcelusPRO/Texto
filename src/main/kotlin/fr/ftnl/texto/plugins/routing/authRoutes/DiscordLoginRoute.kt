package fr.ftnl.texto.plugins.routing.authRoutes

import fr.ftnl.texto.HTTP_CLIENT
import fr.ftnl.texto.database.models.Author
import fr.ftnl.texto.database.models.SocialMedia
import fr.ftnl.texto.plugins.DiscordUser
import fr.ftnl.texto.plugins.UserSession
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.log

@Serializable
data class UserConnection(
    val id: String,
    val name: String,
    val type: String,
    val friend_sync: Boolean,
    val metadata_visibility: Long,
    val show_activity: Boolean,
    val two_way_link: Boolean,
    val verified: Boolean,
    val visibility: Int,
)

fun Route.discordLoginRoute() {
    authenticate("discord_oauth") {
        get("/login") {
            call.respondRedirect("/clb")
        }
        get("/clb") {
            val principal = call.authentication.principal<OAuthAccessTokenResponse.OAuth2>()
            val accessToken = principal?.accessToken ?: return@get
            val response = HTTP_CLIENT.get("https://discord.com/api/users/@me") {
                header("Authorization", "Bearer $accessToken")
            }.bodyAsText()
            val json = Json {
                ignoreUnknownKeys = true
            }
            val result = json.decodeFromString<DiscordUser>(response)
            var user = Author.getByEmail(result.email)
            if (user == null) {
                if (call.application.environment.config.property("users.allowNew").getString().toBoolean())
                    user = Author.create(result.email, result.username,  "https://cdn.discordapp.com/avatars/${result.id}/${result.avatar}")
                else return@get call.respond(HttpStatusCode.Unauthorized)
            }
            user.name = result.username
            user.avatarUrl = "https://cdn.discordapp.com/avatars/${result.id}/${result.avatar}"


            val connectionsText = HTTP_CLIENT.get("https://discord.com/api/users/@me/connections") {
                header("Authorization", "Bearer $accessToken")
            }.bodyAsText()
            val connections = json.decodeFromString<Set<UserConnection>>(connectionsText)
            val cons = connections
                .filter { it.visibility == 1 }
                .mapNotNull {
                    val url = socialMediaUrlTransformer(it.type, it.name, it.id) ?: return@mapNotNull null
                    Pair(socialMediaTypeTransformer(it.type), url)
                }

            cons.forEach {
                if (user.social.none { s -> s.url == it.second }) { SocialMedia.new(user, it.first, it.second) }
            }
            user.social.filter { it.url !in cons.map { c -> c.second } }.forEach { it.deleteOnTransaction() }

            result.avatar = "https://cdn.discordapp.com/avatars/${result.id}/${result.avatar}"
            val session = UserSession(result, result.email.hashCode())
            call.sessions.set(session)
            println(user.apiKey)
            call.respondRedirect("/")
        }
    }
}

fun socialMediaUrlTransformer(type: String, name: String, id: String): String? {
    return when(type) {
        "steam" -> "https://steamcommunity.com/profiles/$id"
        "twitch" -> "https://twitch.tv/$name"
        "twitter" -> "https://twitter.com/$name"
        "youtube" -> "https://youtube.com/channel/$id"
        "domain" -> id
        else -> null
    }
}
fun socialMediaTypeTransformer(type: String): String {
    return when(type) {
        "domain" -> "fa-solid fa-link"
        "steam" -> "fa-brands fa-steam-symbol"
        "twitch" -> "fa-brands fa-twitch"
        "twitter" -> "fa-brands fa-x-twitter"
        "youtube" -> "fa-brands fa-youtube"
        else -> type
    }
}