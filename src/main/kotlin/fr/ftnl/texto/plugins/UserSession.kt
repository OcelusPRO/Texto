package fr.ftnl.texto.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours


@Serializable
data class UserSession(
    var discordUser: DiscordUser? = null,
    var twitchUser: TwitchUser? = null,
    val connectedEmailHash: String,
): Principal

@Serializable
data class DiscordUser(
    var id: String,
    var username: String,
    var avatar: String = "",
    var email: String = "",
)

@Serializable
data class TwitchUser(
    var id: String,
    var display_name: String,
    var profile_image_url: String = "",
    var email: String = "",
)

@Serializable
data class ViewSession(
    var pageViews: MutableSet<String> = mutableSetOf()
)

fun Application.configSessions(){
    install(Sessions) {
        val config = this@configSessions.environment.config
        val secretEncryptKey = hex(config.property("security.sessions.secretEncryptKey").getString())
        val secretSignKey = hex(config.property("security.sessions.secretSignKey").getString())

        cookie<UserSession>("user-session") {
            transform(SessionTransportTransformerEncrypt(secretEncryptKey, secretSignKey))
            cookie.path = "/"
            cookie.maxAgeInSeconds = 7.days.inWholeSeconds
            cookie.extensions["SameSite"] = "lax"
        }

        cookie<ViewSession>("pages"){
            transform(SessionTransportTransformerEncrypt(secretEncryptKey, secretSignKey))
            cookie.path = "/"
            cookie.maxAgeInSeconds = 6.hours.inWholeSeconds
            cookie.extensions["SameSite"] = "lax"
        }
    }
}
