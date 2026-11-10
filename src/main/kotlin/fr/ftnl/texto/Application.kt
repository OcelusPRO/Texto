package fr.ftnl.texto

import fr.ftnl.texto.auth.*
import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.db.Db
import fr.ftnl.texto.legacy.LegacyDates
import fr.ftnl.texto.legacy.LegacyImporter
import fr.ftnl.texto.routes.CreatePostLimit
import fr.ftnl.texto.routes.apiRoutes
import fr.ftnl.texto.routes.legacyRoutes
import fr.ftnl.texto.service.*
import fr.ftnl.texto.util.ApiException
import fr.ftnl.texto.web.OgImageRenderer
import fr.ftnl.texto.web.webRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

fun main() {
    val cfg = AppConfig.fromEnv()
    Db.init(cfg.db)
    embeddedServer(Netty, port = cfg.port, host = "0.0.0.0") { module(cfg) }.start(wait = true)
}

fun Application.module(cfg: AppConfig) {
    val log = LoggerFactory.getLogger("Texto")
    val users = UserService(cfg)
    val posts = PostService(cfg)
    val admin = AdminService(users)
    val sync = ProfileSync(users, HttpProviderDirectory(cfg))
    authResolver = AuthResolver(users)

    LegacyImporter(cfg, users).runIfNeeded()?.let { log.info("Import v1 : {}", it) }
    val legacyDates = LegacyDates(cfg)
    legacyDates.restoreIfNeeded()
    posts.collapseRedundantRevisionsOnce()?.let { log.info("Versions sans changement de contenu supprimées : {}", it) }
    posts.restoreUpdatedAtOnce()?.let { log.info("Dates de modification restaurées : {}", it) }
    posts.pruneRedundantTagsOnce()?.let { log.info("Tags racine redondants retirés de {} post(s)", it) }
    // Une seule fois : tags de version (factorio/x.y) sur les plans déjà présents.
    posts.backfillFactorioTagsOnce()?.let { log.info("Tags de version Factorio ajoutés à {} post(s)", it) }

    install(DefaultHeaders) {
        header("X-Frame-Options", "SAMEORIGIN")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
    }
    install(XForwardedHeaders)
    install(AutoHeadResponse)
    install(Compression)
    install(CallLogging) { filter { it.request.path().startsWith("/api") } }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    installSessions(cfg)
    install(RateLimit) {
        register(CreatePostLimit) {
            rateLimiter(limit = cfg.rateLimitPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.identity()?.userId ?: call.request.origin.remoteAddress }
        }
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorDto(e.code, e.message)) }
        exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", e.message)) }
        exception<Throwable> { call, e ->
            log.error("Erreur sur ${call.request.httpMethod.value} ${call.request.uri}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal_error"))
        }
    }

    intercept(ApplicationCallPipeline.Plugins) {
        if (!call.checkOrigin(cfg)) {
            call.respond(HttpStatusCode.Forbidden, ErrorDto("bad_origin"))
            finish()
        }
    }

    routing {
        swaggerUI(path = "api/docs", swaggerFile = "openapi/documentation.yaml")
        oauthRoutes(cfg, users)
        passkeyRoutes(PasskeyService(cfg))
        apiRoutes(cfg, users, posts, admin, sync, legacyDates)
        legacyRoutes(cfg, users, posts)
        webRoutes(cfg, posts, users, OgImageRenderer())
    }

    // Purge périodique des posts expirés.
    launch {
        while (true) {
            runCatching { posts.purgeExpired() }
                .onSuccess { if (it > 0) log.info("{} post(s) expiré(s) supprimé(s)", it) }
                .onFailure { log.warn("Purge des posts expirés impossible", it) }
            delay(10.minutes)
        }
    }
}
