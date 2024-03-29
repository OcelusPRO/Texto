package fr.ftnl.texto.plugins.routing.pages

import com.google.gson.Gson
import fr.ftnl.texto.database.models.Author
import fr.ftnl.texto.database.models.Texto
import fr.ftnl.texto.ext.md5
import fr.ftnl.texto.plugins.UserSession
import fr.ftnl.texto.plugins.ViewSession
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.mustache.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import java.io.File

fun Route.getPage(){
    fun getPageInfo(pageId: String, increment: Boolean): PageInfo? {
        val file = File("./pages/${pageId.md5()}")
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

        if (increment && texto != null) texto.views++
        
        val info = PageInfo(
            TextoInfo(
                content = file.readText(),
                title = texto!!.name,
                description = texto.description,
                views = texto.views
            ),
            AuthorInfo(
                avatar = texto.author.avatarUrl,
                name = texto.author.name,
                id = texto.author.id,
                social = texto.author.social.map { SocialMedia(it.url, it.iconName) },
                views = texto.author.textos.sumOf { it.views },
                texto = texto.author.textos.size
            )
        )
        return info
    }

    get("/{texto_id}"){
        val textoId = call.parameters["texto_id"]?: return@get call.respond(HttpStatusCode.NotFound)
        val userSession = call.sessions.get<UserSession>()
        val viewSession = call.sessions.get<ViewSession>() ?: ViewSession()

        val isNotBot = call.request.userAgent()?.contains("bot") == false
        val newView = viewSession.pageViews.contains(textoId).not()

        val info = getPageInfo(textoId, isNotBot and newView) ?: return@get call.respond(HttpStatusCode.NotFound)
        if (newView) viewSession.pageViews.add(textoId)

        if (userSession?.connectedEmailHash != null) {
            val texto = Texto.get(textoId) ?: return@get call.respond(HttpStatusCode.NotFound)
            val author = userSession.connectedEmailHash.let { Author.getByEmailHash(it) }
            info.connected = UserInfo(
                author?.name ?: "",
                author?.avatarUrl ?: "",
                true,
                author?.id == texto.author.id
            )
        } else info.connected = UserInfo()

        call.sessions.set(viewSession)
        call.respondTemplate("code.hbs", info)
    }

    get("/raw/{texto_id}"){
        val textoId = call.parameters["texto_id"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val info = getPageInfo(textoId, false) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(HttpStatusCode.OK, info.texto.content)
    }

    authenticate("auth-session") {
        get("/delete/{texto_id}"){
            val textoId = call.parameters["texto_id"] ?: return@get call.respond(HttpStatusCode.NotFound)
            val texto = Texto.get(textoId) ?: return@get call.respond(HttpStatusCode.NotFound)
            val author = call.sessions.get<UserSession>()?.connectedEmailHash?.let { Author.getByEmailHash(it) }?.id
            if (author != texto.author.id) return@get call.respond(HttpStatusCode.Unauthorized)

            call.respondRedirect("/author/${texto.author.id}")
            texto.deleteOnTransaction()
            getPageInfo(textoId, false)
        }

        get("/new"){
            val query = call.request.queryParameters["from"]
            val pageInfo = (if (query != null) getPageInfo(query, false) else PageInfo()) ?: PageInfo()

            val session = call.sessions.get<UserSession>()
            val author = session?.connectedEmailHash?.let { Author.getByEmailHash(it) }

            if (author == null){
                println("author est null")
                return@get call.respond(HttpStatusCode.Unauthorized)
            }

            val authorInfo = AuthorInfo(
                author.avatarUrl,
                author.name,
                author.id,
                author.social.map { SocialMedia(it.url, it.iconName) },
                author.textos.sumOf { it.views },
                author.textos.size
            )

            pageInfo.author = authorInfo
            pageInfo.connected = UserInfo(
                author.name ?: "",
                author.avatarUrl ?: "",
                true,
                false
            )

            call.respondTemplate("new.hbs", pageInfo)
        }
    }
}


@Serializable
data class PageInfo(
    val texto: TextoInfo = TextoInfo(),
    var author: AuthorInfo = AuthorInfo(),
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
private val String.clean
    get() = this.lowercase().replace(" ", "_")

@Serializable
data class AuthorInfo(
    val avatar: String = "",
    val name: String = "",
    val cleanName: String = "",
    val social: String = "[]",
    var views: Int = 0,
    val textos: Int = 0
) {
    constructor(avatar: String, name: String, id: EntityID<Int>, social: List<SocialMedia>, views: Int, texto: Int) :
            this(avatar, name, id.toString(), Gson().toJson(social), views, texto)
}

@Serializable
data class SocialMedia(
    val url: String = "",
    val icon: String = ""
)
