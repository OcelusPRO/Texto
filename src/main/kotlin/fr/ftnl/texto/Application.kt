package fr.ftnl.texto

import fr.ftnl.texto.database.DBManager
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.server.application.*


val HTTP_CLIENT = HttpClient(CIO){

}

suspend fun main(args: Array<String>) {
    DBManager()
    
}


fun Application.module() {
}
