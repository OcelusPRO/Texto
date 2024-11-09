package fr.ftnl.texto.plugins

import com.github.mustachejava.DefaultMustacheFactory
import io.ktor.server.application.*
import io.ktor.server.mustache.Mustache
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Application.configureTemplating() {
    install(Mustache) {
        mustacheFactory = DefaultMustacheFactory(File("./web/templates"))
    }
}
