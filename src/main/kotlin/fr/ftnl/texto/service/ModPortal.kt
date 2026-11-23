package fr.ftnl.texto.service

import fr.ftnl.texto.auth.httpClient
import fr.ftnl.texto.util.badRequest
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ModRef(val name: String, val version: String? = null)

@Serializable
data class ModLookupInput(val mods: List<ModRef>)

/** Infos d'un mod du portail officiel, pour la version demandée (ou la dernière si elle est introuvable). */
@Serializable
data class ModInfoDto(
    val name: String,
    val found: Boolean,
    val title: String? = null,
    val summary: String? = null,
    val owner: String? = null,
    val category: String? = null,
    val thumbnail: String? = null,
    val latestVersion: String? = null,
    /** Version dont viennent [dependencies] et [downloadUrl] (null si aucune). */
    val version: String? = null,
    val factorioVersion: String? = null,
    val dependencies: List<String> = emptyList(),
    val downloadUrl: String? = null,
    val downloads: Long? = null,
)

/**
 * Proxy vers l'API publique de mods.factorio.com (le portail ne permet pas les appels depuis le navigateur).
 * Les fiches sont gardées en mémoire 12 h (1 h pour un mod introuvable).
 */
class ModPortal {
    private val log = LoggerFactory.getLogger(ModPortal::class.java)

    private class Release(val version: String, val factorioVersion: String?, val dependencies: List<String>, val downloadUrl: String?)
    private class Entry(val at: Long, val mod: JsonObject?, val releases: List<Release>)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val gate = Semaphore(8)

    companion object {
        const val PORTAL = "https://mods.factorio.com"
        const val MAX_MODS = 600
        private const val TTL = 12 * 3600_000L
        private const val TTL_MISSING = 3600_000L
        private val NAME = Regex("^[A-Za-z0-9_\\-. +]{1,100}$")
        /** Mods livrés avec le jeu : pas de page sur le portail. */
        val BUILTIN = setOf("base", "core", "space-age", "quality", "elevated-rails")
    }

    suspend fun lookup(input: ModLookupInput): List<ModInfoDto> {
        if (input.mods.size > MAX_MODS) badRequest("too_many")
        val refs = input.mods.filter { NAME.matches(it.name) && it.name !in BUILTIN }.distinctBy { it.name }
        return coroutineScope {
            refs.map { ref -> async { gate.withPermit { info(ref) } } }.awaitAll()
        }
    }

    private suspend fun info(ref: ModRef): ModInfoDto {
        val e = entry(ref.name) ?: return ModInfoDto(ref.name, found = false)
        val m = e.mod ?: return ModInfoDto(ref.name, found = false)
        val latest = e.releases.lastOrNull()
        val rel = e.releases.lastOrNull { it.version == ref.version } ?: latest
        return ModInfoDto(
            name = ref.name,
            found = true,
            title = m.str("title"),
            summary = m.str("summary"),
            owner = m.str("owner"),
            category = m.str("category"),
            thumbnail = m.str("thumbnail")?.takeUnless { it.endsWith("/.thumb.png") }?.let { if (it.startsWith("http")) it else "https://assets-mod.factorio.com$it" },
            latestVersion = latest?.version,
            version = rel?.version,
            factorioVersion = rel?.factorioVersion,
            dependencies = rel?.dependencies.orEmpty(),
            downloadUrl = rel?.downloadUrl?.let { PORTAL + it },
            downloads = m["downloads_count"]?.jsonPrimitive?.longOrNull,
        )
    }

    private suspend fun entry(name: String): Entry? {
        val now = System.currentTimeMillis()
        cache[name]?.let { if (now - it.at < (if (it.mod == null) TTL_MISSING else TTL)) return it }
        val fetched = try {
            val body = httpClient.get("$PORTAL/api/mods/${name.encodeURLPathPart()}/full").bodyAsText()
            val mod = Json.parseToJsonElement(body).jsonObject
            val releases = mod["releases"]?.jsonArray.orEmpty().mapNotNull { r ->
                val o = r as? JsonObject ?: return@mapNotNull null
                val info = o["info_json"] as? JsonObject
                Release(
                    version = o.str("version") ?: return@mapNotNull null,
                    factorioVersion = info?.str("factorio_version"),
                    dependencies = (info?.get("dependencies") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    downloadUrl = o.str("download_url"),
                )
            }
            // Les descriptions et changelogs sont lourds : on ne garde que ce qui sert.
            val slim = JsonObject(mod.filterKeys { it in setOf("title", "summary", "owner", "category", "thumbnail", "downloads_count") })
            Entry(now, slim, releases)
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) Entry(now, null, emptyList()) else null.also { log.warn("Portail des mods ($name) : ${e.response.status}") }
        } catch (e: Exception) {
            log.warn("Portail des mods ($name) injoignable : ${e.message}")
            null
        }
        if (fetched != null) cache[name] = fetched
        return fetched ?: cache[name]
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
