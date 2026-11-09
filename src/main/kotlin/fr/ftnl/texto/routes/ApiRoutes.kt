package fr.ftnl.texto.routes

import fr.ftnl.texto.auth.*
import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.legacy.LegacyDates
import fr.ftnl.texto.util.SocialRules
import fr.ftnl.texto.service.*
import fr.ftnl.texto.util.badRequest
import fr.ftnl.texto.util.notFound
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import java.io.File

val CreatePostLimit = RateLimitName("create_post")

private fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull() ?: badRequest("invalid_$name")

private fun ApplicationCall.intQuery(name: String, default: Int): Int =
    request.queryParameters[name]?.toIntOrNull() ?: default

private fun isBot(call: ApplicationCall): Boolean {
    val ua = call.request.userAgent()?.lowercase() ?: return true
    return listOf("bot", "crawler", "spider", "preview", "embed", "curl", "wget", "python").any { it in ua }
}

/** Ne compte une vue qu'une fois par navigateur (cookie signé des 100 dernières clés vues). */
private fun ApplicationCall.registerView(key: String): Boolean {
    if (isBot(this)) return false
    val views = sessions.get<ViewSession>() ?: ViewSession()
    if (key in views.keys) return false
    sessions.set(ViewSession((views.keys + key).takeLast(100)))
    return true
}

fun Route.apiRoutes(cfg: AppConfig, users: UserService, posts: PostService, admin: AdminService, sync: ProfileSync, legacyDates: LegacyDates) {
    route("/api") {
        get("/config") {
            call.respond(
                PublicConfigDto(
                    siteName = cfg.siteName,
                    baseUrl = cfg.baseUrl,
                    providers = cfg.providers(),
                    maxContentLength = cfg.maxContentLength,
                    factorioAssets = File(cfg.factorioAssetsDir, "manifest.json").isFile,
                    allowNewUsers = users.registrationOpen(),
                    socials = SocialRules.ALL.map { d -> SocialDefDto(d.key, d.label, d.placeholder, d.rules.map { SocialRuleDto(it.host, it.pathPrefix) }, d.embed, d.allowHttp) },
                    fbeUrl = cfg.fbeUrl,
                )
            )
        }

        // ------------------------------------------------------------ compte courant
        route("/me") {
            get {
                val id = call.requireUser()
                val me = users.me(id.userId)
                call.respond(id.impersonatorId?.let { me.copy(impersonatedBy = users.mini(it)) } ?: me)
            }
            patch {
                val id = call.requireUser(allowToken = false, allowImpersonation = false)
                call.respond(users.updateMe(id.userId, call.receive()))
            }
            delete {
                val id = call.requireUser(allowToken = false, allowImpersonation = false)
                users.deleteMe(id.userId)
                call.sessions.clear<UserSession>()
                call.respond(HttpStatusCode.NoContent)
            }
            put("/socials") {
                val id = call.requireUser(allowToken = false, allowImpersonation = false)
                users.setSocials(id.userId, call.receive<List<SocialDto>>())
                call.respond(users.me(id.userId).socials)
            }
            delete("/accounts/{provider}") {
                val id = call.requireUser(allowToken = false, allowImpersonation = false)
                users.unlink(id.userId, call.parameters["provider"]!!)
                call.respond(users.me(id.userId))
            }

            get("/posts") {
                val id = call.requireUser("posts:read")
                val qp = call.request.queryParameters
                val folder = qp["folder"]?.let { if (it == "none") -1L else it.toLongOrNull() }
                call.respond(
                    posts.listOwn(
                        id.userId, PostQuery(
                            q = qp["q"],
                            tags = qp.getAll("tag").orEmpty().flatMap { it.split(',') }.filter(String::isNotBlank),
                            folderId = folder,
                            recursive = qp["recursive"] == "1",
                            kind = qp["kind"],
                            language = qp["lang"],
                            visibility = qp["visibility"],
                            sort = qp["sort"] ?: "updated",
                            page = call.intQuery("page", 1),
                            pageSize = call.intQuery("pageSize", 30),
                        )
                    )
                )
            }

            route("/folders") {
                get { call.respond(FolderService.list(call.requireUser("posts:read").userId)) }
                post { call.respond(HttpStatusCode.Created, FolderService.create(call.requireUser("posts:write").userId, call.receive())) }
                patch("/{id}") {
                    call.respond(FolderService.update(call.requireUser("posts:write").userId, call.longParam("id"), call.receive()))
                }
                delete("/{id}") {
                    FolderService.delete(call.requireUser("posts:write").userId, call.longParam("id"))
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/tags") {
                get { call.respond(TagService.list(call.requireUser("posts:read").userId)) }
                post { call.respond(HttpStatusCode.Created, TagService.create(call.requireUser("posts:write").userId, call.receive())) }
                patch("/{id}") {
                    call.respond(TagService.update(call.requireUser("posts:write").userId, call.longParam("id"), call.receive()))
                }
                delete("/{id}") {
                    TagService.delete(call.requireUser("posts:write").userId, call.longParam("id"))
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/tokens") {
                get { call.respond(TokenService.list(call.requireUser(allowToken = false, allowImpersonation = false).userId)) }
                post {
                    call.respond(HttpStatusCode.Created, TokenService.create(call.requireUser(allowToken = false, allowImpersonation = false).userId, call.receive()))
                }
                delete("/{id}") {
                    TokenService.delete(call.requireUser(allowToken = false, allowImpersonation = false).userId, call.longParam("id"))
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        // ------------------------------------------------------------ posts
        route("/posts") {
            rateLimit(CreatePostLimit) {
                post {
                    val id = call.requireUser("posts:write")
                    call.respond(HttpStatusCode.Created, posts.create(id.userId, call.receive()))
                }
            }
            route("/{key}") {
                get {
                    val key = call.parameters["key"]!!
                    val password = call.request.header("X-Post-Password")
                    when (val r = posts.read(call.viewer(), key, password, call.registerView(key))) {
                        is PostService.ReadResult.Ok -> {
                            if (!r.post.burned) sync.touchAuthorOfPost(key)
                            call.respond(r.post)
                        }
                        PostService.ReadResult.PasswordRequired ->
                            call.respond(HttpStatusCode.Unauthorized, ErrorDto("password_required"))
                        PostService.ReadResult.WrongPassword ->
                            call.respond(HttpStatusCode.Unauthorized, ErrorDto("wrong_password"))
                    }
                }
                patch {
                    val id = call.requireUser("posts:write")
                    call.respond(posts.update(Viewer(id.userId, id.isAdmin), call.parameters["key"]!!, call.receive()))
                }
                delete {
                    val id = call.requireUser("posts:write")
                    posts.delete(Viewer(id.userId, id.isAdmin), call.parameters["key"]!!)
                    call.respond(HttpStatusCode.NoContent)
                }
                put("/thumbnail") {
                    val id = call.requireUser("posts:write")
                    posts.setThumbnail(Viewer(id.userId), call.parameters["key"]!!, call.receive<ByteArray>())
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/revisions") {
                    val id = call.requireUser("posts:read")
                    call.respond(posts.revisions(Viewer(id.userId), call.parameters["key"]!!))
                }
                get("/revisions/{n}") {
                    val id = call.requireUser("posts:read")
                    val n = call.parameters["n"]?.toIntOrNull() ?: notFound()
                    call.respond(posts.revision(Viewer(id.userId), call.parameters["key"]!!, n))
                }
                post("/revisions/{n}/restore") {
                    val id = call.requireUser("posts:write")
                    val n = call.parameters["n"]?.toIntOrNull() ?: notFound()
                    call.respond(posts.restore(Viewer(id.userId), call.parameters["key"]!!, n))
                }
            }
        }

        // ------------------------------------------------------------ profils publics
        get("/users/{handle}") {
            val profile = users.publicProfile(call.parameters["handle"]!!)
            sync.touchHandle(profile.handle)
            call.respond(profile)
        }
        get("/users/{handle}/posts") {
            call.respond(
                posts.listPublicOf(
                    call.parameters["handle"]!!, call.intQuery("page", 1), call.intQuery("pageSize", 30).coerceIn(1, 100),
                    call.request.queryParameters["q"], call.request.queryParameters["folder"]?.toLongOrNull(),
                    call.request.queryParameters["kind"], call.request.queryParameters.getAll("tag").orEmpty(),
                    call.request.queryParameters["sort"] ?: "updated",
                )
            )
        }
        get("/users/{handle}/tags") { call.respond(TagService.publicList(call.parameters["handle"]!!)) }
        get("/users/{handle}/folders") { call.respond(FolderService.publicTree(call.parameters["handle"]!!)) }

        // ------------------------------------------------------------ administration
        route("/admin") {
            get("/stats") { call.requireAdmin(); call.respond(admin.stats()) }
            get("/settings") { call.requireAdmin(); call.respond(AdminSettingsDto(users.registrationOpen())) }
            // Agir en tant qu'un autre compte (tri des dossiers, gestion des tags…). L'admin d'origine est conservé dans la session.
            post("/impersonate/{id}") {
                val adminId = call.requireRealAdmin()
                val target = call.longParam("id")
                users.checkImpersonationTarget(adminId, target)
                call.sessions.set(UserSession(target, System.currentTimeMillis(), impersonatorId = adminId))
                org.slf4j.LoggerFactory.getLogger("Audit").info("Admin {} agit en tant que le compte {}", adminId, target)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/impersonate/stop") {
                val adminId = call.requireRealAdmin()
                val id = call.identity()
                call.sessions.set(UserSession(adminId, System.currentTimeMillis()))
                org.slf4j.LoggerFactory.getLogger("Audit").info("Admin {} revient à son compte (était le compte {})", adminId, id?.userId)
                call.respond(HttpStatusCode.NoContent)
            }
            put("/settings") {
                call.requireAdmin()
                val s = call.receive<AdminSettingsDto>()
                users.setRegistrationOpen(s.allowNewUsers)
                call.respond(s)
            }
            post("/restore-legacy-dates") {
                call.requireAdmin()
                val report = legacyDates.restore()
                call.respondText(report?.toString() ?: "rien a restaurer")
            }
            post("/users/{id}/accounts") {
                val me = call.requireAdmin()
                val input = call.receive<AdminLinkInput>()
                val providerUserId = input.providerUserId.trim()
                users.validateProviderId(input.provider, providerUserId)
                // Pseudo et avatar si on peut les retrouver (Twitch) ; sinon l'ID tient lieu de nom jusqu'à la première connexion.
                val profile = sync.lookup(input.provider, providerUserId)
                admin.linkAccount(me.userId, call.longParam("id"), input.provider, providerUserId, profile?.username, profile?.avatarUrl)
                call.respond(HttpStatusCode.NoContent)
            }
            delete("/users/{id}/accounts/{provider}") {
                val me = call.requireAdmin()
                admin.unlinkAccount(me.userId, call.longParam("id"), call.parameters["provider"]!!)
                call.respond(HttpStatusCode.NoContent)
            }
            delete("/users/{id}") {
                val me = call.requireAdmin()
                admin.deleteUser(me.userId, call.longParam("id"))
                call.respond(HttpStatusCode.NoContent)
            }
            get("/users") {
                call.requireAdmin()
                call.respond(admin.users(call.request.queryParameters["q"], call.intQuery("page", 1), 50))
            }
            patch("/users/{id}") {
                val me = call.requireAdmin()
                admin.patchUser(me.userId, call.longParam("id"), call.receive())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/users/{id}/merge-into/{target}") {
                call.requireAdmin()
                admin.merge(call.longParam("id"), call.longParam("target"))
                call.respond(HttpStatusCode.NoContent)
            }
            get("/posts") {
                call.requireAdmin()
                call.respond(posts.listAll(call.request.queryParameters["q"], call.intQuery("page", 1), 50))
            }
        }
    }
}

/** Compatibilité v1 : envoi par clé API et anciennes URL de profil. */
fun Route.legacyRoutes(cfg: AppConfig, users: UserService, posts: PostService) {
    rateLimit(CreatePostLimit) {
        post("/new-texto/api") {
            val id = call.requireUser("posts:write")
            val data = call.receive<LegacyPostData>()
            val expiresIn = data.expire?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 } ?: (7L * 86400)
            val created = posts.create(
                id.userId,
                PostInput(
                    title = data.title, description = data.description, content = data.content,
                    visibility = if (data.public) "public" else "unlisted", expiresIn = expiresIn,
                )
            )
            call.respondText("${cfg.baseUrl}/${created.key}", status = HttpStatusCode.OK)
        }
    }
    get("/author/{id}") {
        val handle = call.parameters["id"]?.toIntOrNull()?.let(users::handleForLegacyId) ?: notFound()
        call.respondRedirect("/u/$handle", permanent = true)
    }
    get("/raw/{key}") {
        val content = posts.raw(call.viewer(), call.parameters["key"]!!, call.request.header("X-Post-Password"))
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondText(content, ContentType.Text.Plain.withCharset(Charsets.UTF_8))
    }
}
