package fr.ftnl.texto.plugins.routing.authRoutes

import fr.ftnl.texto.database.models.Author
import fr.ftnl.texto.database.models.Texto
import fr.ftnl.texto.database.models.Texto.Companion.get
import fr.ftnl.texto.plugins.UserSession
import fr.ftnl.texto.plugins.routing.pages.UserInfo
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.mustache.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable

fun  Route.login() {
    get {
        val info = LoginData()
        val userSession = call.sessions.get<UserSession>()
        if (userSession?.connectedEmailHash != null) {
            val author = userSession.connectedEmailHash.let { Author.getByEmailHash(it) }
            info.connected = UserInfo(
                author?.name ?: "",
                author?.avatarUrl ?: "",
                true,
                false
            )
        } else info.connected = UserInfo()
        val baseUrl = call.application.environment.config.property("texto.baseUrl").getString()
        info.website.baseUrl = baseUrl
        call.respondTemplate("login.hbs", info)
    }
    route("/discord") { discordLoginRoute() }
    route("/twitch") { twitchLoginRoute() }
    route("/form") { formLoginRoute() }

}

@Serializable
data class LoginData(
    var connected: UserInfo = UserInfo(),
    var website: WebsiteInfo = WebsiteInfo()
)
@Serializable
data class WebsiteInfo(
    var baseUrl: String = ""
)
