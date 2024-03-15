package fr.ftnl.texto

import fr.ftnl.texto.database.DBManager
import fr.ftnl.texto.plugins.*
import fr.ftnl.texto.plugins.routing.configureHTTP
import fr.ftnl.texto.plugins.routing.configureRouting
import fr.ftnl.texto.plugins.routing.configureTemplating
import fr.ftnl.texto.plugins.security.configureSecurity
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.logging.*
import io.ktor.http.*
import io.ktor.server.application.*


fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

val HTTP_CLIENT = HttpClient(CIO){}

fun Application.module() {
    startup()

    configSessions()
    configureSecurity()
    configureHTTP()
    configureMonitoring()
    configureSerialization()
    configureTemplating()
    configureRouting(environment.config)
}

fun Application.startup(){
    DBManager(environment.config)
}