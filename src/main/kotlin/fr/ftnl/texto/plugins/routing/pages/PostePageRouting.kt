package fr.ftnl.texto.plugins.routing.pages

import fr.ftnl.texto.database.models.Author
import fr.ftnl.texto.database.models.Texto
import fr.ftnl.texto.ext.keyGen
import fr.ftnl.texto.ext.md5
import fr.ftnl.texto.plugins.UserSession
import fr.ftnl.texto.plugins.security.modules.ApiSessionPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import org.joda.time.DateTime
import java.io.File

@Serializable
data class PostData(
    val content: String,
    val title: String,
    val description: String,
    val public: Boolean,
    val expire: Long? = null
)

fun newPost(
    data: String,
    name: String,
    description: String,
    user: Author,
    public: Boolean,
    config: ApplicationConfig,
    expire: DateTime?
): String? {
    val key = String.keyGen(config.property("texto.keyLength").getString().toInt())
    val hash = key.md5()
    val folder = File(config.property("texto.dataPath").getString())
    folder.mkdirs()
    if (data.length > config.property("texto.maxLength").getString().toLong()) return null
    val file = File(folder.path + "/" + hash)
    file.createNewFile()
    file.writeText(data)
    Texto.create(user, name, description, expire, hash, public)
    return key
}

fun Route.postPage(){
    route("/new-texto"){

        authenticate("discord_oauth") {
            rateLimit(RateLimitName("post_texto_user")) {
                post("/user") {
                    val author = call.sessions.get<UserSession>()?.discordUser?.email?.let { Author.getByEmail(it) } ?: return@post
                    val postData = call.receive<PostData>()
                    val key = newPost(
                        postData.content,
                        postData.title,
                        postData.description,
                        author,
                        postData.public,
                        call.application.environment.config,
                        postData.expire?.let { DateTime(it) }
                    )
                    val baseUrl = call.application.environment.config.property("texto.baseUrl").getString()
                    call.respond(HttpStatusCode.OK, "$baseUrl/$key")
                }
            }
        }

        authenticate("api-key") {
            rateLimit(RateLimitName("post_texto_api")) {
                post("/api") {
                    val connected = call.principal<ApiSessionPrincipal>() ?: return@post
                    val postData = call.receive<PostData>()
                    val key = newPost(
                        postData.content,
                        postData.title,
                        postData.description,
                        connected.user,
                        postData.public,
                        call.application.environment.config,
                        DateTime.now().plusDays(7)
                    )
                    val baseUrl = call.application.environment.config.property("texto.baseUrl").getString()
                    call.respond(HttpStatusCode.OK, "$baseUrl/$key")
                }
            }
        }
    }


}