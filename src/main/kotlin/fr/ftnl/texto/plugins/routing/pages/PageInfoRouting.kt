package fr.ftnl.texto.plugins.routing.pages

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import fr.ftnl.texto.database.models.Texto
import fr.ftnl.texto.ext.md5
import fr.ftnl.texto.plugins.UserSession
import fr.ftnl.texto.plugins.routing.AuthorInfo
import fr.ftnl.texto.plugins.routing.PageInfo
import fr.ftnl.texto.plugins.routing.SocialMedia
import fr.ftnl.texto.plugins.routing.TextoInfo
import io.ktor.http.*
import io.ktor.server.mustache.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
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

        val session = call.sessions.get<UserSession>() ?: UserSession()
        if(session.pageViews.contains(textoId).not()){
            info.texto.vues++
            info.author.vues++
            pageCache.put(textoId, info)
        }
        session.pageViews.add(textoId)
        call.respondTemplate("code.hbs", info)
    }
}