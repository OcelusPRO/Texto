package fr.ftnl.texto.plugins

import io.ktor.server.application.*
import io.ktor.server.sessions.*
import io.ktor.util.*


data class UserSession(
    val pageViews: MutableSet<String> = mutableSetOf()

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