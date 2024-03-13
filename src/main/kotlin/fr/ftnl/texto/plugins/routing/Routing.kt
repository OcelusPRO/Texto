package fr.ftnl.texto.plugins.routing

import fr.ftnl.texto.database.models.Texto
import fr.ftnl.texto.ext.md5
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.http.content.*
import io.ktor.server.mustache.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.resources.Resources
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Application.configureRouting(config: ApplicationConfig) {
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

        get("/{texto_id}"){
            val textoId = call.parameters["texto_id"]?.md5() ?: return@get call.respond(HttpStatusCode.NotFound)

            val file = File("./pages/$textoId")
            val texto = Texto.get(textoId) ?: return@get call.respond(HttpStatusCode.NotFound)

            val info = PageInfo(
                TextoInfo(
                    content = file.readText(),
                    title = texto.name,
                    description = texto.description
                ),
                AuthorInfo(
                    avatar = "",
                    name = texto.author.name,
                    social = texto.author.social.map { SocialMedia(it.url, it.iconName) },
                    vues = texto.author.textos.sumOf { it.vues },
                    texto = texto.author.textos.size
                )
            )

            call.respondTemplate("code.hbs", PageInfo(TextoInfo(file.readText())))
        }

        staticResources("/static", "static")
        staticResources("/", "static/pages")
    }
}

data class PageInfo(
    val paste: TextoInfo = TextoInfo(),
    val author: AuthorInfo = AuthorInfo()
)
data class TextoInfo(
    val content: String = "",
    val title: String = "",
    val description: String = ""
)
private fun String.toClean() = this.lowercase().replace(" ", "_")
data class AuthorInfo(
    val avatar: String = "",
    val name: String = "",
    val cleanName: String = "",
    val social: String = "",
    val vues: Int = 0,
    val texto: Int = 0
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