package fr.ftnl.texto.plugins.security.modules

import fr.ftnl.texto.database.models.Author
import fr.ftnl.texto.plugins.security.config.apiKey
import io.ktor.server.auth.*


data class ApiSessionPrincipal(
    val key: String,
    val user: Author
): Principal
fun AuthenticationConfig.apiSecurityConfig(){
    apiKey("api-key") {
        headerName = "X-API-Key"
        validate {fromHeader ->
            Author.get(fromHeader)?.let { ApiSessionPrincipal(fromHeader, it) }
        }
    }
}