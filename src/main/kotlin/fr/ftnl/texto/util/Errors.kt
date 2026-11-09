package fr.ftnl.texto.util

import io.ktor.http.HttpStatusCode

class ApiException(val status: HttpStatusCode, val code: String, override val message: String? = null) : RuntimeException(message)

fun notFound(code: String = "not_found"): Nothing = throw ApiException(HttpStatusCode.NotFound, code)
fun forbidden(code: String = "forbidden"): Nothing = throw ApiException(HttpStatusCode.Forbidden, code)
fun unauthorized(code: String = "unauthorized"): Nothing = throw ApiException(HttpStatusCode.Unauthorized, code)
fun badRequest(code: String, message: String? = null): Nothing = throw ApiException(HttpStatusCode.BadRequest, code, message)
fun conflict(code: String, message: String? = null): Nothing = throw ApiException(HttpStatusCode.Conflict, code, message)
