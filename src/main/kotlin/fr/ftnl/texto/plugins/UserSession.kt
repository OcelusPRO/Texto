package fr.ftnl.texto.plugins

import io.ktor.server.application.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


data class UserSession(
    var pageViews: MutableSet<String> = mutableSetOf(),
    var discordUser: DiscordUser? = null,
    var connections: Set<UserConnection> = mutableSetOf(),
)

@Serializable
data class DiscordUser(
    var id: String,
    var username: String, //@SerializedName("discriminator") var discriminator: String,
    var avatar: String? = null,
    var verified: Boolean? = null,
    var email: String? = null,
    var flags: Int? = null,
    var banner: String? = null,
    @SerialName("accent_color") var accentColor: Int? = null,
    @SerialName("premium_type") var premiumType: Int? = null,
    @SerialName("public_flags") var publicFlags: Int? = null,
)
@Serializable
data class UserConnection(
    val id: String,
    val name: String,
    val type: String,
    @SerialName("friend_sync") val friendSync: Boolean,
    @SerialName("metadata_visibility") val metadataVisibility: Long,
    @SerialName("show_activity") val showActivity: Boolean,
    @SerialName("two_way_link") val twoWayLink: Boolean,
    val verified: Boolean,
    val visibility: Int,
)


fun Application.configSessions(){
    install(Sessions) {
        val config = this@configSessions.environment.config
        val secretEncryptKey = hex(config.property("security.sessions.secretEncryptKey").getString())
        val secretSignKey = hex(config.property("security.sessions.secretSignKey").getString())
        header<UserSession>(config.property("security.sessions.sessionName").getString()) {
            transform(SessionTransportTransformerEncrypt(secretEncryptKey, secretSignKey))
        }
    }
}