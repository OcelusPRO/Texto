package fr.ftnl.texto.plugins.security.config

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*

class ApiKeyAuthenticationProvider internal constructor(
    configuration: Configuration
) : AuthenticationProvider(configuration) {
    private val headerName: String = configuration.headerName
    private val authenticationFunction = configuration.authenticationFunction
    private val challengeFunction = configuration.challengeFunction
    private val authScheme = configuration.authScheme

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val apiKey = context.call.request.header(headerName)
        val principal = apiKey?.let { authenticationFunction(context.call, it) }

        val cause = when {
            apiKey == null -> AuthenticationFailedCause.NoCredentials
            principal == null -> AuthenticationFailedCause.InvalidCredentials
            else -> null
        }

        if (cause != null) {
            context.challenge(authScheme, cause) { challenge, call ->
                challengeFunction(call)

                challenge.complete()
            }
        }
        if (principal != null) {
            context.principal(principal)
        }
    }

    class Configuration internal constructor(name: String?) : Config(name) {

        internal lateinit var authenticationFunction: ApiKeyAuthenticationFunction

        internal var challengeFunction: ApiKeyAuthChallengeFunction = { call ->
            call.respond(HttpStatusCode.Unauthorized)
        }

        var authScheme: String = "apiKey"
        var headerName: String = "X-Api-Key"

        fun validate(body: suspend ApplicationCall.(String) -> Principal?) {
            authenticationFunction = body
        }

        fun challenge(body: ApiKeyAuthChallengeFunction) {
            challengeFunction = body
        }
    }
}

fun AuthenticationConfig.apiKey(
    name: String? = null,
    configure: ApiKeyAuthenticationProvider.Configuration.() -> Unit
) {
    val provider = ApiKeyAuthenticationProvider(ApiKeyAuthenticationProvider.Configuration(name).apply(configure))
    register(provider)
}

typealias ApiKeyAuthenticationFunction = suspend ApplicationCall.(String) -> Principal?
typealias ApiKeyAuthChallengeFunction = suspend (ApplicationCall) -> Unit