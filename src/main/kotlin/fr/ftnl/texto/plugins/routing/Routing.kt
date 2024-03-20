package fr.ftnl.texto.plugins.routing

import fr.ftnl.texto.plugins.UserSession
import fr.ftnl.texto.plugins.routing.authRoutes.login
import fr.ftnl.texto.plugins.routing.authRoutes.register
import fr.ftnl.texto.plugins.routing.pages.getPage
import fr.ftnl.texto.plugins.routing.pages.postPage
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.resources.Resources
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import java.io.File
import kotlin.time.Duration.Companion.seconds

fun Application.configureRouting() {
    val path = if (System.getenv("dev") != null) "." else "/opt/program"

    install(AutoHeadResponse)
    install(DoubleReceive)
    install(Resources)
    install(StatusPages) {

        status(HttpStatusCode.NotFound) { call, status -> call.respondText(text = "404: Page Not Found", status = status) }
        status(HttpStatusCode.Unauthorized) { call, status ->
            println(call.sessions.get<UserSession>())
            call.respond(status, "Unauthorized")
        }

        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
        exception<Throwable> { call, cause ->
            cause.printStackTrace()
            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
        }
    }
    val cfg = environment.config
    install(RateLimit) {
        register(RateLimitName("post_texto_user")) {
            rateLimiter(
                limit = cfg.property("texto.ratesLimites.userLimite").getString().toInt(),
                refillPeriod = cfg.property("texto.ratesLimites.userRefillSeconds").getString().toInt().seconds
            )
            requestKey { applicationCall ->
                applicationCall.sessions.get<UserSession>()!!
            }
        }
        register(RateLimitName("post_texto_api")) {
            rateLimiter(
                limit = cfg.property("texto.ratesLimites.apiLimite").getString().toInt(),
                refillPeriod = cfg.property("texto.ratesLimites.apiRefillSeconds").getString().toInt().seconds
            )
            requestKey { applicationCall ->
                val header = cfg.property("security.api.header").getString()
                applicationCall.request.header(header)!!
            }
        }
    }

    routing {
        route("/login") { login() }
        route("/register") { register() }
        getPage()
        postPage()

        staticFiles("/static", File("$path/static"))
        staticFiles("/", File("$path/static/pages"))
    }
}
