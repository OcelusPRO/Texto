package fr.ftnl.texto.plugins.routing.authors

import com.google.gson.Gson
import fr.ftnl.texto.database.models.Author
import fr.ftnl.texto.plugins.UserSession
import fr.ftnl.texto.plugins.routing.pages.AuthorInfo
import fr.ftnl.texto.plugins.routing.pages.SocialMedia
import fr.ftnl.texto.plugins.routing.pages.UserInfo
import io.ktor.http.*
import io.ktor.server.mustache.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*


fun Route.author(){

    data class TextoData(
        val title: String,
        val description: String,
        val views: Int,
        val code: String
    )
    data class PageInfo(
        var textos: String = "",
        var author: AuthorInfo = AuthorInfo(),
        var connected: UserInfo = UserInfo()

    )

    get("/{id}"){
        val authorId = call.parameters["id"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
        val author = Author.getById(authorId) ?: return@get call.respond(HttpStatusCode.NotFound)

        val info = PageInfo()

        val authorInfo = AuthorInfo(
            author.avatarUrl,
            author.name,
            author.id,
            author.social.map { SocialMedia(it.url, it.iconName) },
            author.textos.sumOf { it.views },
            author.textos.size
        )
        info.author = authorInfo
        var onlyPublic = true

        val userSession = call.sessions.get<UserSession>()
        if (userSession?.connectedEmailHash != null) {
            val author = userSession.connectedEmailHash.let { Author.getByEmailHash(it) }
            info.connected = UserInfo(
                author?.name ?: "",
                author?.avatarUrl ?: "",
                true,
                false
            )
            if (author?.emailHash == userSession.connectedEmailHash) onlyPublic = false
        } else info.connected = UserInfo()


        var textos = author.textos
        if (onlyPublic) textos = textos.filter { it.public }
        val textosData = textos.map { TextoData(
            it.name,
            it.description,
            it.views,
            it.keyHash
        ) }
        info.textos = Gson().toJson(textosData)


        call.respondTemplate("authors.hbs", info)
    }
}
