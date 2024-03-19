package fr.ftnl.texto.plugins.routing.authRoutes

import fr.ftnl.texto.plugins.UserSession
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

fun Route.formLoginRoute() {
    authenticate("auth-form") {
        post("/") {
            val principal = call.principal<UserSession>()
            call.sessions.set(principal)
            call.respondRedirect("/")
        }
    }
}
