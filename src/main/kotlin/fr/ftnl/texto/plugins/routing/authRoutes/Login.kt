package fr.ftnl.texto.plugins.routing.authRoutes

import io.ktor.server.application.*
import io.ktor.server.mustache.*
import io.ktor.server.routing.*

fun  Route.login() {
    get {
        call.respondTemplate("login.hbs")
    }
    route("/discord") { discordLoginRoute() }
    route("/twitch") { twitchLoginRoute() }
    route("/form") { formLoginRoute() }

}