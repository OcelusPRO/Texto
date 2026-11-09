package fr.ftnl.texto.util

import java.net.URI

/** Noms des liens sociaux : normalise les anciennes valeurs (classes FontAwesome de la v1, alias) vers le catalogue. */
object SocialIcons {
    private val FA_PREFIXES = setOf("fa", "fab", "fas", "far", "fa-brands", "fa-solid", "fa-regular")

    private val ALIASES = mapOf(
        "link" to "website", "globe" to "website", "web" to "website",
        "x-twitter" to "x", "twitter" to "x", "twitter-x" to "x",
        "ko-fi" to "kofi",
    )

    /** "fa-brands fa-x-twitter" -> "x" ; "GitHub" -> "github". Vide : déduit du domaine de [url]. */
    fun normalize(raw: String, url: String? = null): String {
        val parts = raw.trim().lowercase().split(' ', '\t').filter { it.isNotEmpty() && it !in FA_PREFIXES }
            .map { it.removePrefix("fa-") }
        val key = parts.firstOrNull { it.isNotEmpty() }
        val host = runCatching { URI(url ?: "").host }.getOrNull()?.removePrefix("www.")
        val base = key ?: host?.substringBefore('.')?.ifEmpty { null } ?: "link"
        return (ALIASES[base] ?: base).take(50)
    }

    /** Comme [normalize], mais une icône hors catalogue devient un lien web classique ("website"). */
    fun resolve(raw: String, url: String? = null): String =
        normalize(raw, url).takeIf { SocialRules.get(it) != null } ?: "website"

    fun label(key: String): String = SocialRules.label(key)
}
