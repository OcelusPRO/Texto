package fr.ftnl.texto.plugins.routing

import fr.ftnl.texto.plugins.UserSession
import fr.ftnl.texto.plugins.routing.authRoutes.discordLoginRoute
import fr.ftnl.texto.plugins.routing.pages.getPage
import fr.ftnl.texto.plugins.routing.pages.postPage
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.resources.Resources
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlin.time.Duration.Companion.seconds

fun Application.configureRouting(config: ApplicationConfig) {
    install(AutoHeadResponse)
    install(DoubleReceive)
    install(Resources)
    install(StatusPages) {

        status(HttpStatusCode.NotFound) { call, status -> call.respondText(text = "404: Page Not Found", status = status) }

        exception<Throwable> { call, cause ->
            cause.printStackTrace()
            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
        }
    }
    install(RateLimit) {
        register(RateLimitName("post_texto_user")) {
            rateLimiter(limit = 2, refillPeriod = 60.seconds)
            requestKey { applicationCall ->
                applicationCall.sessions.get<UserSession>()!!
            }
        }
        register(RateLimitName("post_texto_api")) {
            rateLimiter(limit = 30, refillPeriod = 60.seconds)
            requestKey { applicationCall ->
                val header = applicationCall.application.environment.config.property("security.api.header").getString()
                applicationCall.request.header(header)!!
            }
        }
    }

    routing {
        discordLoginRoute()
        getPage()
        postPage()


        staticResources("/static", "static")
        staticResources("/", "static/pages")
    }
}

data class PageInfo(
    val texto: TextoInfo = TextoInfo(),
    val author: AuthorInfo = AuthorInfo()
)
data class TextoInfo(
    val content: String = "",
    val title: String = "",
    val description: String = "",
    var vues: Int = 0
)
private fun String.toClean() = this.lowercase().replace(" ", "_")
data class AuthorInfo(
    val avatar: String = "",
    val name: String = "",
    val cleanName: String = "",
    val social: String = "",
    var vues: Int = 0,
    val textos: Int = 0
) {
    constructor(
        avatar: String,
        name: String,
        social: List<SocialMedia>,
        vues: Int,
        texto: Int
    ) : this(
        avatar,
        name,
        name.toClean(),
        social.map { it.toHtmlMedia() }.joinToString { it },
        vues,
        texto
    )
}
data class SocialMedia(
    val url: String = "",
    val icon: String = ""
){
    fun toHtmlMedia() = "<a href=\"$url\"><i class=\"fa fa-$icon\"></i></a>"
}