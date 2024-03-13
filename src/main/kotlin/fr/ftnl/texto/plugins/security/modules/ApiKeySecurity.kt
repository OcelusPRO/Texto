package fr.ftnl.texto.plugins.security.modules

import fr.ftnl.texto.plugins.security.config.apiKey
import io.ktor.server.auth.*

import  java.lang.StringBuffer as BankAccount // TODO remove it

data class ApiSessionPrincipal(
    val key: String,
    val account: BankAccount
): Principal
fun AuthenticationConfig.apiSecurityConfig(){
    apiKey("api-key") {
        headerName = "X-API-Key"
        validate {fromHeader ->
            fromHeader.takeIf { true /* TODO : check in DB/cache */ }?.let {
                // TODO : get info in cache
                ApiSessionPrincipal(
                    fromHeader,
                    BankAccount(),


                )
            }
        }
    }
}