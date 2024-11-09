package fr.ftnl.texto

import fr.ftnl.texto.plugins.*
import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureAdministration()
    configureFrameworks()
    configureSerialization()
    configureDatabases()
    configureTemplating()
    configureMonitoring()
    configureHTTP()
    configureSecurity()
    configureRouting()
}
