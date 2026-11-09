package fr.ftnl.texto.web

import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.service.PostService
import fr.ftnl.texto.service.UserPublicDto
import fr.ftnl.texto.service.UserService
import fr.ftnl.texto.util.ApiException
import fr.ftnl.texto.util.SocialIcons
import fr.ftnl.texto.util.SocialRules
import io.ktor.http.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.io.File

private val KEY_REGEX = Regex("^[A-Za-z0-9]{4,32}$")
private const val THEME_COLOR = "#1e1f22"
private const val EMBED_LIMIT = 3000

/**
 * Sert le frontend React (web/dist embarqué dans le jar) et injecte côté serveur les balises <head>
 * des aperçus de liens : OpenGraph/Twitter, et le JSON des « component embeds » Discord.
 * Discord n'exécute pas de JavaScript : ces balises doivent être dans le HTML initial.
 */
fun Route.webRoutes(cfg: AppConfig, posts: PostService, users: UserService, og: OgImageRenderer) {
    val indexTemplate: String? by lazy {
        WebRoutes::class.java.classLoader.getResource("web/index.html")?.readText()
    }

    staticResources("/assets", "web/assets") {
        cacheControl { listOf(CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Public)) }
    }
    staticResources("/cli", "cli")
    staticFiles("/factorio-assets", File(cfg.factorioAssetsDir)) {
        cacheControl { listOf(CacheControl.MaxAge(maxAgeSeconds = 86_400, visibility = CacheControl.Visibility.Public)) }
    }

    // Image de partage : miniature FBE de l'auteur si elle existe, sinon fiche de statistiques.
    get("/og/{file}") {
        val key = call.parameters["file"]!!.removeSuffix(".png")
        val png = posts.thumbnail(key) ?: posts.preview(key)?.let { og.render(it, cfg.siteName) }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 600, visibility = CacheControl.Visibility.Public))
        call.respondBytes(png, ContentType.Image.PNG)
    }

    get("/robots.txt") {
        call.respondText("User-agent: *\nDisallow: /api/\nDisallow: /raw/\nDisallow: /settings\nDisallow: /admin\n")
    }

    get("/{path...}") {
        val segments = call.parameters.getAll("path").orEmpty()
        if (segments.firstOrNull() == "api") return@get call.respond(HttpStatusCode.NotFound)
        // Ancien identifiant d'un utilisateur : redirection permanente vers l'actuel.
        if (segments.size >= 2 && segments[0] == "u") {
            val canonical = users.canonicalHandle(segments[1])
            if (canonical != null && canonical != segments[1]) {
                val rest = segments.drop(2).joinToString("") { "/" + it.encodeURLPathPart() }
                return@get call.respondRedirect("/u/$canonical$rest", permanent = true)
            }
        }
        // Fichiers statiques à la racine de web/dist (favicon, manifest...).
        if (segments.size == 1 && '.' in segments[0]) {
            val res = WebRoutes::class.java.classLoader.getResource("web/${segments[0]}")
            if (res != null && !segments[0].contains("..")) {
                return@get call.respondBytes(res.readBytes(), ContentType.defaultForFilePath(segments[0]))
            }
            return@get call.respond(HttpStatusCode.NotFound)
        }
        val template = indexTemplate ?: return@get call.respondText(
            "Frontend non compilé : lancez `npm run build` dans web/ (ou utilisez le serveur Vite en dev).",
            status = HttpStatusCode.ServiceUnavailable,
        )
        val head = buildHead(cfg, segments, posts, users)
        call.respondText(template.replace("<!--texto-head-->", head), ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
}

private object WebRoutes

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun meta(property: String, content: String, name: Boolean = false) =
    "<meta ${if (name) "name" else "property"}=\"$property\" content=\"${esc(content)}\">"

private fun embedScript(json: String) =
    // `</` ne doit jamais apparaître dans un <script> inline.
    "<script id=\"discord:component-embed\" type=\"application/json\">${json.replace("</", "<\\/")}</script>"

private fun plural(n: Number, one: String, many: String) = "$n ${if (n.toLong() > 1) many else one}"

private fun buildHead(cfg: AppConfig, segments: List<String>, posts: PostService, users: UserService): String {
    val default = listOf(
        "<title>${esc(cfg.siteName)}</title>",
        meta("og:site_name", cfg.siteName),
        meta("og:title", cfg.siteName),
        meta("og:type", "website"),
        meta("theme-color", THEME_COLOR, name = true),
        meta("description", "Partage de code, de notes et de plans Factorio.", name = true),
    ).joinToString("\n")

    if (segments.size == 1 && KEY_REGEX.matches(segments[0])) {
        val key = segments[0]
        val p = posts.preview(key) ?: return default
        val s = p.summary
        val url = "${cfg.baseUrl}/$key"
        val image = "${cfg.baseUrl}/og/$key.png?v=${s.revision}"
        val kindLabel = when (s.kind) {
            "factorio" -> "Plan Factorio"
            "markdown" -> "Markdown"
            "text" -> "Texte"
            else -> s.language ?: "Code"
        }
        val desc = s.description.ifBlank { "$kindLabel · par ${p.author.displayName}" }.take(300)
        val tags = mutableListOf(
            "<title>${esc(s.title)} · ${esc(cfg.siteName)}</title>",
            meta("og:site_name", cfg.siteName),
            meta("og:title", s.title),
            meta("og:description", desc),
            meta("og:type", "article"),
            meta("og:url", url),
            meta("og:image", image),
            meta("og:image:width", "1200"),
            meta("og:image:height", "630"),
            meta("twitter:card", "summary_large_image", name = true),
            meta("description", desc, name = true),
            "<link rel=\"canonical\" href=\"${esc(url)}\">",
            meta("theme-color", THEME_COLOR, name = true),
        )
        if (s.visibility != "public") tags += meta("robots", "noindex", name = true)
        postEmbed(cfg, p, url, image, kindLabel)?.let { tags += embedScript(it) }
        return tags.joinToString("\n")
    }

    if (segments.size >= 2 && segments[0] == "u") {
        val profile = try { users.publicProfile(segments[1]) } catch (_: ApiException) { return default }
        val url = "${cfg.baseUrl}/u/${profile.handle}"
        val stats = "${plural(profile.publicPosts, "post public", "posts publics")} · ${plural(profile.totalViews, "vue", "vues")}"
        val tags = mutableListOf(
            "<title>${esc(profile.displayName)} · ${esc(cfg.siteName)}</title>",
            meta("og:site_name", cfg.siteName),
            meta("og:title", profile.displayName),
            meta("og:description", profile.bio.ifBlank { stats }.take(300)),
            meta("og:type", "profile"),
            meta("og:url", url),
            meta("twitter:card", "summary", name = true),
            "<link rel=\"canonical\" href=\"${esc(url)}\">",
            meta("theme-color", THEME_COLOR, name = true),
        )
        profile.avatarUrl?.let { tags += meta("og:image", it) }
        profileEmbed(cfg, profile, url)?.let { tags += embedScript(it) }
        return tags.joinToString("\n")
    }
    return default
}

private fun JsonObjectBuilder.container(components: JsonArray) {
    putJsonObject("component") {
        put("type", 17)
        put("components", components)
    }
}

private fun JsonArrayBuilder.text(content: String) = addJsonObject { put("type", 10); put("content", content) }

private fun JsonArrayBuilder.separator() = addJsonObject { put("type", 14); put("divider", true); put("spacing", 1) }

/** Texte + vignette (avatar) à droite, ou texte seul sans avatar. */
private fun JsonArrayBuilder.header(content: String, thumbnail: String?) {
    if (thumbnail != null) {
        addJsonObject {
            put("type", 9)
            putJsonArray("components") { text(content) }
            putJsonObject("accessory") {
                put("type", 11)
                putJsonObject("media") { put("url", thumbnail) }
            }
        }
    } else {
        text(content)
    }
}

/** Boutons de lien (style 5) répartis en rangées de 5 au plus. */
private fun JsonArrayBuilder.linkButtons(buttons: List<Pair<String, String>>) {
    buttons.chunked(5).forEach { row ->
        addJsonObject {
            put("type", 1)
            putJsonArray("components") {
                row.forEach { (label, url) -> addJsonObject { put("type", 2); put("style", 5); put("label", label.take(80)); put("url", url) } }
            }
        }
    }
}

/** Retourne le premier JSON qui tient dans la limite de 3000 octets, en réduisant progressivement le contenu. */
private fun fit(attempts: List<() -> String>): String? =
    attempts.asSequence().map { it() }.firstOrNull { it.replace("</", "<\\/").toByteArray().size <= EMBED_LIMIT }

/** Embed d'un post : titre, statistiques, description, et la miniature FBE seulement si elle existe. */
private fun postEmbed(cfg: AppConfig, p: PostService.PreviewInfo, url: String, image: String, kindLabel: String): String? {
    val s = p.summary
    fun build(descLimit: Int, withImage: Boolean): String {
        val head = buildString {
            append("## ").append(s.title.take(120))
            append("\n-# ").append(kindLabel).append(" · ").append(p.author.displayName.take(60))
            append(" · ").append(plural(s.views, "vue", "vues"))
            if (s.revision > 1) append(" · v").append(s.revision)
            if (s.tags.isNotEmpty()) append(" · ").append(s.tags.take(5).joinToString(" ") { "`${it.name}`" })
        }
        val components = buildJsonArray {
            header(head, p.author.avatarUrl)
            if (s.description.isNotBlank() && descLimit > 0) text(s.description.take(descLimit))
            if (withImage) addJsonObject {
                put("type", 12)
                putJsonArray("items") { addJsonObject { putJsonObject("media") { put("url", image) } } }
            }
            linkButtons(buildList {
                add("Ouvrir" to url)
                if (!s.hasPassword && s.burnAfterViews == null) add("Raw" to "${cfg.baseUrl}/raw/${s.key}")
                add("Profil de ${p.author.displayName.take(30)}" to "${cfg.baseUrl}/u/${p.author.handle}")
            })
        }
        return buildJsonObject { container(components) }.toString()
    }
    val withImage = s.hasThumbnail && !s.hasPassword && s.burnAfterViews == null
    return fit(listOf({ build(300, withImage) }, { build(120, withImage) }, { build(0, withImage) }, { build(0, false) }))
}

/** Liens affichés en boutons dans l'aperçu Discord : Twitch, YouTube, GitHub et Discord uniquement (liens valides). */
private fun embeddedSocials(u: UserPublicDto): List<Pair<String, String>> =
    u.socials.map { SocialIcons.resolve(it.icon, it.url) to it.url }
        .filter { (key, url) -> SocialRules.isEmbedded(key) && SocialRules.valid(key, url) }

/** Embed d'un profil : identité, statistiques, bio, bouton vers le profil et liens des réseaux. */
private fun profileEmbed(cfg: AppConfig, u: UserPublicDto, url: String): String? {
    fun build(bioLimit: Int, socials: Int): String {
        val head = buildString {
            append("## ").append(u.displayName.take(80))
            append("\n-# @").append(u.handle).append(" · ")
            append(plural(u.publicPosts, "post public", "posts publics")).append(" · ").append(plural(u.totalViews, "vue", "vues"))
        }
        val components = buildJsonArray {
            header(head, u.avatarUrl)
            if (u.bio.isNotBlank() && bioLimit > 0) text(u.bio.take(bioLimit))
            separator()
            linkButtons(buildList {
                add("Voir le profil" to url)
                embeddedSocials(u).take(socials).forEach { (key, url) -> add(SocialRules.label(key) to url) }
            })
        }
        return buildJsonObject { container(components) }.toString()
    }
    val n = embeddedSocials(u).size.coerceAtMost(9)
    return fit(listOf({ build(400, n) }, { build(150, n) }, { build(0, n) }, { build(0, 4) }, { build(0, 0) }))
}
