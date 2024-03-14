package fr.ftnl.texto.plugins.routing

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

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
        fun getPageInfo(pageId: String): PageInfo? {
            val file = File("./pages/$pageId")
            val texto = Texto.get(pageId) ?: return null

            val info = PageInfo(
                TextoInfo(
                    content = file.readText(),
                    title = texto.name,
                    description = texto.description,
                    vues = texto.vues +1
                ),
                AuthorInfo(
                    avatar = texto.author.avatarUrl,
                    name = texto.author.name,
                    social = texto.author.social.map { SocialMedia(it.url, it.iconName) },
                    vues = texto.author.textos.sumOf { it.vues }+1,
                    texto = texto.author.textos.size
                )
            )
            return info
        }
        val pageCache: Cache<String, PageInfo?> = Caffeine.newBuilder()
            .expireAfterWrite(5.minutes.toJavaDuration())
            .removalListener { key: String?, value: PageInfo?, cause ->
                if (key == null) return@removalListener
                if (value == null) return@removalListener
                val texto = Texto.get(key) ?: return@removalListener
                texto.vues = value.texto.vues
            }
            .build()


        get("/{texto_id}"){
            val textoId = call.parameters["texto_id"]?.md5() ?: return@get call.respond(HttpStatusCode.NotFound)
            val info = pageCache.get(textoId, ::getPageInfo) ?: return@get call.respond(HttpStatusCode.NotFound)
            pageCache.put(textoId, info.copy(
                texto = TextoInfo(
                    info.texto.content,
                    info.texto.title,
                    info.texto.description,
                    info.texto.vues + 1 // TODO : if session already view current texto dont increment
                )
            ))
            call.respondTemplate("code.hbs", info)
        }

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
    val vues: Int = 0
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