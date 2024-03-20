package fr.ftnl.texto.plugins.routing.authRoutes

import fr.ftnl.texto.database.models.Author
import fr.ftnl.texto.plugins.UserSession
import fr.ftnl.texto.plugins.routing.pages.UserInfo
import io.ktor.http.*
import io.ktor.server.mustache.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

fun Route.formRegisterRoute() {
    post("/") {
        /*
        * val formParameters = call.receiveParameters()
        val userName = formParameters["username"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val email = formParameters["email"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val password = formParameters["password"] ?: return@post call.respond(HttpStatusCode.BadRequest)

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

        val passwordRegex = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@\$!%*?&])[A-Za-z\\d@\$!%*?&]{8,}\$".toRegex()
        if (password.matches(passwordRegex).not()) {
            info.website.error = """
                 Le mot de passe ne respecte pas les conditions minimales de sécurité :
                 - min 8 caractères
                 - min 1 majuscule
                 - min 1 minuscule
                 - min 1 chiffre
                 - min un caractère spécial
            """.trimIndent()
            call.respondTemplate("register.hbs", info)
        }


        call.respondRedirect("/login")
        * */

        call.respond(HttpStatusCode.NotImplemented)
    }

}
