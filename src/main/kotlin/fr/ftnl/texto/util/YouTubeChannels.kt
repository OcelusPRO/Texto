package fr.ftnl.texto.util

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Nom affiché des liens de chaînes YouTube : on lit la page publique de la chaîne pour en tirer
 * son vrai nom (« As2piK ») et, pour un lien `youtube.com/channel/UC…`, son adresse `@handle`.
 */
object YouTubeChannels {
    /** [url] : lien à enregistrer (converti en `@handle` si possible) ; [name] : nom de la chaîne. */
    data class Info(val url: String, val name: String?)

    private val CHANNEL_PATH = Regex("^/channel/(UC[A-Za-z0-9_-]{22})/?")
    /** Chemins désignant une chaîne (et non une vidéo, une playlist…). */
    private val CHANNEL_LIKE = Regex("^/(@[^/]+|channel/[^/]+|c/[^/]+|user/[^/]+)/?")
    private val VANITY = Regex("\"vanityChannelUrl\":\"https?://(?:www\\.)?youtube\\.com/(@[^\"/?]+)\"")
    private val CANONICAL = Regex("\"canonicalBaseUrl\":\"/(@[^\"/?]+)\"")
    private val OG_TITLE = Regex("<meta property=\"og:title\" content=\"([^\"]*)\"")

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private fun uri(url: String): URI? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        return uri.takeIf { host == "youtube.com" }
    }

    /** ID de chaîne (`UC…`) d'un lien `youtube.com/channel/…`, sinon null. */
    fun channelId(url: String): String? = uri(url)?.let { CHANNEL_PATH.find(it.path.orEmpty())?.groupValues?.get(1) }

    /** Le lien désigne-t-il une chaîne YouTube ? */
    fun isChannel(url: String): Boolean = uri(url)?.let { CHANNEL_LIKE.containsMatchIn(it.path.orEmpty()) } == true

    /** Extrait le handle (`@nom`) de la page HTML d'une chaîne. */
    fun handleFromPage(html: String): String? = (VANITY.find(html) ?: CANONICAL.find(html))?.groupValues?.get(1)

    /** Extrait le nom de la chaîne (balise og:title) de sa page HTML. */
    fun nameFromPage(html: String): String? =
        OG_TITLE.find(html)?.groupValues?.get(1)?.let(::unescapeHtml)?.trim()?.takeIf { it.isNotEmpty() }?.take(100)

    private fun unescapeHtml(s: String): String = s
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** Nom (et lien `@handle`) de la chaîne désignée par [url], ou null si ce n'est pas une chaîne ou si YouTube ne répond pas. */
    fun lookup(url: String): Info? {
        if (!isChannel(url)) return null
        val target = channelId(url)?.let { "https://www.youtube.com/channel/$it" } ?: url.trim()
        val req = runCatching {
            HttpRequest.newBuilder(URI(target))
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "Mozilla/5.0 (compatible; Texto)")
                .header("Accept-Language", "en")
                // Évite la page de consentement aux cookies servie aux visiteurs européens.
                .header("Cookie", "SOCS=CAI; CONSENT=YES+1")
                .GET().build()
        }.getOrNull() ?: return null
        val html = runCatching { client.send(req, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
            ?.takeIf { it.statusCode() == 200 }?.body() ?: return null
        val name = nameFromPage(html)
        val newUrl = if (channelId(url) != null) handleFromPage(html)?.let { "https://www.youtube.com/$it" } ?: url.trim() else url.trim()
        if (name == null && newUrl == url.trim()) return null
        return Info(newUrl, name)
    }
}
