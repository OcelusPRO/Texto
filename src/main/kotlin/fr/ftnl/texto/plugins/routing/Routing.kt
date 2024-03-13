package fr.ftnl.texto.plugins.routing

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.mustache.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.Resources
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.File

fun Application.configureRouting() {
    install(AutoHeadResponse)
    install(DoubleReceive)
    install(Resources)
    install(StatusPages) {

        status(HttpStatusCode.NotFound) { call, status -> call.respondText(text = "404: Page Not Found", status = status) }

        exception<Throwable> { call, cause ->
            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
        }
    }
    routing {
        get("/") {
            call.respondText("Hello World!")
        }

        get("/{past_id}"){
            val pastId = call.parameters["past_id"] ?: return@get call.respond(HttpStatusCode.NotFound)

            val content = File("./pages/$pastId").readText()

            call.respondTemplate("code.hbs", PageInfo(PasteInfo(content)))
        }

        staticResources("/static", "static")
    }
}

data class PageInfo(
    val paste: PasteInfo = PasteInfo(),
    val author: AuthorInfo = AuthorInfo()
)

data class PasteInfo(
    val content: String = "",
    val title: String = "",
    val description: String = ""
)
private fun String.toClean() = this.lowercase().replace(" ", "_")
data class AuthorInfo(
    val avatar: String = "",
    val name: String = "",
    val cleanName: String = "",
    val social: String = ""
) {
    constructor(avatar: String, name: String, social: List<SocialMedia>) : this(
        avatar,
        name,
        name.toClean(),
        social.map { it.toHtmlMedia() }.joinToString { it }
    )
}
data class SocialMedia(
    val url: String = "",
    val icon: String = ""
){
    fun toHtmlMedia() = "<a href=\"$url\"><i class=\"fa fa-$icon\"></i></a>"
}