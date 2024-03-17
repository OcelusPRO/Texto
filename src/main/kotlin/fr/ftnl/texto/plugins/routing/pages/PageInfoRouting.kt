package fr.ftnl.texto.plugins.routing.pages

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.Gson
import fr.ftnl.texto.database.models.Author
import fr.ftnl.texto.database.models.Texto
import fr.ftnl.texto.ext.md5
import fr.ftnl.texto.plugins.UserSession
import fr.ftnl.texto.plugins.ViewSession
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.mustache.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

fun Route.getPage(){
    fun getPageInfo(pageId: String): PageInfo? {
        val file = File("./pages/$pageId")
        if (file.exists().not()) return null

        val texto = Texto.get(pageId)

        var badPage = false
        when {
            texto == null -> { badPage = true }
            texto.expireAt?.isBeforeNow == true -> { badPage = true }
        }
        if (badPage) {
            file.delete()
            texto?.deleteOnTransaction()
            return null
        }

        val info = PageInfo(
            TextoInfo(
                content = file.readText(),
                title = texto!!.name,
                description = texto.description,
                views = texto.vues
            ),
            AuthorInfo(
                avatar = texto.author.avatarUrl,
                name = texto.author.name,
                social = texto.author.social.map { SocialMedia(it.url, it.iconName) },
                vues = texto.author.textos.sumOf { it.vues },
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
            texto.vues = value.texto.views
        }
        .build()

    get("/{texto_id}"){
        val textoId = call.parameters["texto_id"]?.md5() ?: return@get call.respond(HttpStatusCode.NotFound)
        val info = pageCache.get(textoId, ::getPageInfo) ?: return@get call.respond(HttpStatusCode.NotFound)

        val userSession = call.sessions.get<UserSession>()
        val viewSession = call.sessions.get<ViewSession>() ?: ViewSession()

        if(viewSession.pageViews.contains(textoId).not()){
            info.texto.views+=1
            info.author.views+=1
            pageCache.put(textoId, info)
        }
        viewSession.pageViews.add(textoId)

        if (userSession?.discordUser != null) {
            val texto = Texto.get(textoId) ?: return@get call.respond(HttpStatusCode.NotFound)
            val author = userSession.discordUser?.email?.let { Author.getByEmail(it) }
            info.connected = UserInfo(
                userSession.discordUser?.username ?: "",
                userSession.discordUser?.avatar ?: "",
                true,
                author?.id == texto.author.id
            )
        } else info.connected = UserInfo()

        call.sessions.set(viewSession)
        call.respondTemplate("code.hbs", info)
    }

    get("/raw/{texto_id}"){
        val textoId = call.parameters["texto_id"]?.md5() ?: return@get call.respond(HttpStatusCode.NotFound)
        val info = pageCache.get(textoId, ::getPageInfo) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(HttpStatusCode.OK, info.texto.content)
    }

    authenticate("discord_oauth") {// TODO change it whene twitch login implementation
        get("/{texto_id}/delete"){
            val textoId = call.parameters["texto_id"]?.md5() ?: return@get call.respond(HttpStatusCode.NotFound)
            val texto = Texto.get(textoId) ?: return@get call.respond(HttpStatusCode.NotFound)
            val author = call.sessions.get<UserSession>()?.discordUser?.email?.let { Author.getByEmail(it) }?.id
            if (author != texto.author.id) return@get call.respond(HttpStatusCode.Unauthorized)

            call.respondRedirect("/author/${texto.author.name.toClean()}")
            texto.deleteOnTransaction()
            getPageInfo(textoId)
        }
    }
}


@Serializable
data class PageInfo(
    val texto: TextoInfo = TextoInfo(),
    val author: AuthorInfo = AuthorInfo(),
    var connected: UserInfo = UserInfo()
)

@Serializable
data class UserInfo(
    val userName: String = "",
    val avatar: String = "",
    val connected: Boolean = false,
    val canDelete: Boolean = false
)

@Serializable
data class TextoInfo(
    val content: String = "",
    val title: String = "",
    val description: String = "",
    var views: Int = 0
)
private fun String.toClean() = this.lowercase().replace(" ", "_")

@Serializable
data class AuthorInfo(
    val avatar: String = "",
    val name: String = "",
    val cleanName: String = "",
    val social: String = "[]",
    var views: Int = 0,
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
        Gson().toJson(social),
        vues,
        texto
    )
}

@Serializable
data class SocialMedia(
    val url: String = "",
    val icon: String = ""
)