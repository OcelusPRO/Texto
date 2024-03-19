package fr.ftnl.texto.plugins.routing.authRoutes

import fr.ftnl.texto.HTTP_CLIENT
import fr.ftnl.texto.database.models.Author
import fr.ftnl.texto.database.models.SocialMedia
import fr.ftnl.texto.database.models.hashSha3512
import fr.ftnl.texto.plugins.TwitchUser
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

@Serializable
data class TwitchResponse(
    val data: List<Datum>
)
@Serializable
data class Datum (
    val id: String,
    val display_name: String,
    val profile_image_url: String,
    val email: String,
)

fun Route.twitchLoginRoute() {
    authenticate("twitch_oauth") {
        get {
            call.sessions.clear<UserSession>()
            call.respondRedirect("/clb")
        }
        get("/clb") {
            val principal = call.authentication.principal<OAuthAccessTokenResponse.OAuth2>()
            val accessToken = principal?.accessToken ?: return@get
            val response = HTTP_CLIENT.get("https://api.twitch.tv/helix/users") {
                header("Authorization", "Bearer $accessToken")
                header("Client-Id", call.application.environment.config.property("security.twitchOauth.clientId").getString())
            }.bodyAsText()
            val json = Json { ignoreUnknownKeys = true }

            val result = json.decodeFromString<TwitchResponse>(response).data.first()
            val tUser: TwitchUser = TwitchUser(
                result.id,
                result.display_name,
                result.profile_image_url,
                result.email
            )
            var user = Author.getByEmail(tUser.email)
            if (user == null) {
                if (call.application.environment.config.property("users.allowNew").getString().toBoolean())
                    user = Author.create(tUser.email, tUser.display_name,  tUser.profile_image_url)
                else return@get call.respond(HttpStatusCode.Unauthorized)
            }
            user.name = tUser.display_name
            user.avatarUrl = result.profile_image_url

            val session = UserSession(
                twitchUser = tUser,
                connectedEmailHash = hashSha3512(tUser.email)
            )
            call.sessions.set(session)
            println(user.apiKey)
            call.respondRedirect("/")
        }
    }
}
