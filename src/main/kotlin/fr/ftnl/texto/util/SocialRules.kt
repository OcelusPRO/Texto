package fr.ftnl.texto.util

import java.net.URI

/**
 * Catalogue des icônes de liens sociaux, avec pour chacune les règles du lien associé
 * (un lien Twitch ne peut pas porter l'icône Discord, etc.).
 */
object SocialRules {
    /** [host] "*" : n'importe quel hôte. [pathPrefix] : le chemin doit commencer par ce préfixe. */
    data class HostRule(val host: String, val pathPrefix: String? = null)

    /** [embed] : l'icône donne un bouton dans les aperçus Discord du profil. */
    data class Def(val key: String, val label: String, val placeholder: String, val rules: List<HostRule>, val embed: Boolean = false, val allowHttp: Boolean = false)

    val ALL: List<Def> = listOf(
        Def("website", "Site web", "https://exemple.fr", listOf(HostRule("*")), allowHttp = true),
        Def("twitch", "Twitch", "https://twitch.tv/pseudo", listOf(HostRule("twitch.tv")), embed = true),
        Def("x", "X", "https://x.com/pseudo", listOf(HostRule("x.com"), HostRule("twitter.com"))),
        Def("github", "GitHub", "https://github.com/pseudo", listOf(HostRule("github.com")), embed = true),
        Def(
            "discord", "Discord", "https://discord.gg/code-d-invitation",
            listOf(HostRule("discord.gg"), HostRule("discord.com", "/invite/"), HostRule("discordapp.com", "/invite/")), embed = true,
        ),
        Def("youtube", "YouTube", "https://youtube.com/@chaine", listOf(HostRule("youtube.com"), HostRule("youtu.be")), embed = true),
        Def("spotify", "Spotify", "https://open.spotify.com/user/id", listOf(HostRule("spotify.com"))),
        Def("steam", "Steam", "https://steamcommunity.com/id/pseudo", listOf(HostRule("steamcommunity.com"), HostRule("steampowered.com"))),
        Def("instagram", "Instagram", "https://instagram.com/pseudo", listOf(HostRule("instagram.com"))),
        Def("tiktok", "TikTok", "https://tiktok.com/@pseudo", listOf(HostRule("tiktok.com"))),
        Def("reddit", "Reddit", "https://reddit.com/user/pseudo", listOf(HostRule("reddit.com"))),
        Def("bluesky", "Bluesky", "https://bsky.app/profile/pseudo", listOf(HostRule("bsky.app"))),
        Def("mastodon", "Mastodon", "https://instance.social/@pseudo", listOf(HostRule("*", "/@"))),
    )

    private val byKey = ALL.associateBy { it.key }

    fun get(key: String): Def? = byKey[key]

    fun label(key: String): String = byKey[key]?.label ?: key.replaceFirstChar { it.uppercase() }

    fun isEmbedded(key: String): Boolean = byKey[key]?.embed == true

    /** Le lien [url] est-il acceptable pour l'icône [key] ? */
    fun valid(key: String, url: String): Boolean {
        val def = byKey[key] ?: return false
        if (url.length > 512) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && !(def.allowHttp && scheme == "http")) return false
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("mobile.") ?: return false
        if (!host.contains('.')) return false
        val path = uri.path.orEmpty()
        return def.rules.any { r ->
            val hostOk = r.host == "*" || host == r.host || host.endsWith("." + r.host)
            val pathOk = if (r.pathPrefix != null) path.startsWith(r.pathPrefix) && path.length > r.pathPrefix.length
            else def.key == "website" || path.length > 1
            hostOk && pathOk
        }
    }
}
