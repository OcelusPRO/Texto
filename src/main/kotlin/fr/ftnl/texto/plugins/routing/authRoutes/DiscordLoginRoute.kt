package fr.ftnl.texto.plugins.routing.authRoutes

import fr.ftnl.texto.HTTP_CLIENT
import fr.ftnl.texto.database.models.Author
import fr.ftnl.texto.database.models.SocialMedia
import fr.ftnl.texto.plugins.DiscordUser
import fr.ftnl.texto.plugins.UserConnection
import fr.ftnl.texto.plugins.UserSession
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.json.Json

fun Route.discordLoginRoute() {
    authenticate("discord_oauth") {
        get("/login") {
            call.sessions.clear("user_session")
            call.sessions.set(UserSession())
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
            var user = Author.get(result.id.toLong())
            if (user == null) {
                if (call.application.environment.config.property("users.allowNew").getString().toBoolean())
                    user = Author.create(result.id.toLong(), result.username, result.avatar)
                else return@get call.respond(HttpStatusCode.Unauthorized)
            }
            user.name = result.username
            user.avatarUrl = result.avatar ?: "/static/images/Unknown_person.jpg"


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

            var session = call.sessions.get<UserSession>()
            if (session == null) session = UserSession(mutableSetOf(), result, connections)
            else session = UserSession(session.pageViews, result, connections)
            call.sessions.set(session)
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
        "domain" -> "link"
        else -> type
    }
}