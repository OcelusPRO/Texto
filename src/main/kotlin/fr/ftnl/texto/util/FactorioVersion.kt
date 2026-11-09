package fr.ftnl.texto.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

/** Version du jeu qui a produit un plan Factorio (chaîne « 0eNq… »), lue sans rien dépendre du client. */
object FactorioVersion {
    private val ROOTS = listOf("blueprint", "blueprint_book", "upgrade_planner", "deconstruction_planner")

    /** « 2.0 » (majeure.mineure, sans le patch) ou null si la chaîne n'est pas un plan lisible. */
    fun of(content: String): String? = try {
        val raw = content.filterNot(Char::isWhitespace)
        if (raw.length < 8 || raw[0] != '0') null else decode(raw.substring(1))
    } catch (_: Exception) {
        null
    }

    private fun decode(b64: String): String? {
        val compressed = Base64.getDecoder().decode(b64)
        val inflater = Inflater()
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        // Garde-fou : un plan légitime reste très en dessous de cette taille.
        while (!inflater.finished() && out.size() < 64_000_000) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inflater.end()
        val root = Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
        val body: JsonObject = ROOTS.firstNotNullOfOrNull { root[it] as? JsonObject } ?: return null
        val packed = body["version"]?.jsonPrimitive?.longOrNull ?: return null
        val major = (packed ushr 48) and 0xFFFF
        val minor = (packed ushr 32) and 0xFFFF
        return if (major == 0L) null else "$major.$minor"
    }

    /** Nom du tag automatique d'un post Factorio, par exemple « factorio/2.0 ». */
    fun tagFor(content: String): String? = of(content)?.let { "factorio/$it" }
}
