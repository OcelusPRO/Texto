package fr.ftnl.texto.plugins.routing

import com.github.mustachejava.DefaultMustacheFactory
import io.ktor.server.application.*
import io.ktor.server.mustache.Mustache
import java.io.File

fun Application.configureTemplating() {

    val path = if (System.getenv("dev") != null) "." else "/opt/program"


    install(Mustache) {
        mustacheFactory = DefaultMustacheFactory(File("$path/templates"))
    }
}
