package fr.ftnl.texto

import fr.ftnl.texto.plugins.*
import fr.ftnl.texto.plugins.routing.configureHTTP
import fr.ftnl.texto.plugins.routing.configureRouting
import fr.ftnl.texto.plugins.routing.configureTemplating
import fr.ftnl.texto.plugins.security.configureSecurity
import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureSecurity()
    configureHTTP()
    configureMonitoring()
    configureSerialization()
    configureTemplating()
    configureRouting()
}
