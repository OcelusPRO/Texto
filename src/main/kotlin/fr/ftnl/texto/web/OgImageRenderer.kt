package fr.ftnl.texto.web

import fr.ftnl.texto.service.PostService
import java.awt.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import javax.imageio.ImageIO

/**
 * Image de partage (1200x630) d'un post : une fiche de statistiques (jamais un extrait du contenu),
 * dans la charte du site : anthracite, police à chasse fixe, sans accent de couleur.
 * Pour un plan Factorio, c'est la miniature rendue par FBE qui est servie à la place quand elle existe.
 */
class OgImageRenderer {
    private val cache: MutableMap<String, ByteArray> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 200
        }
    )

    private val bg = Color(0x1e, 0x1f, 0x22)
    private val panel = Color(0x26, 0x27, 0x2b)
    private val line = Color(0x3a, 0x3b, 0x40)
    private val text = Color(0xe4, 0xe4, 0xe7)
    private val muted = Color(0x8b, 0x8d, 0x94)

    private val mono: String = pickFont(listOf("JetBrains Mono", "Cascadia Mono", "DejaVu Sans Mono", "Consolas", "Menlo"), Font.MONOSPACED)

    private fun pickFont(candidates: List<String>, fallback: String): String {
        val available = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }
            .getOrDefault(emptySet())
        return candidates.firstOrNull { it in available } ?: fallback
    }

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of("Europe/Paris"))

    fun render(p: PostService.PreviewInfo, siteName: String): ByteArray {
        val s = p.summary
        val cacheKey = "${s.key}:${s.revision}:${s.views / 25}:${s.tags.size}"
        cache[cacheKey]?.let { return it }
        val w = 1200
        val h = 630
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = bg
            g.fillRect(0, 0, w, h)

            // En-tête : nom du site, comme la barre du haut de l'interface.
            g.font = Font(mono, Font.BOLD, 28)
            g.color = text
            g.drawString(siteName.lowercase(), 60, 80)
            g.color = line
            g.fillRect(0, 108, w, 2)

            // Titre sur deux lignes au plus.
            g.font = Font(mono, Font.BOLD, 58)
            g.color = text
            val titleLines = wrap(g, s.title, w - 120, 2)
            titleLines.forEachIndexed { i, l -> g.drawString(l, 60, 195 + i * 70) }
            val titleBottom = 195 + (titleLines.size - 1) * 70

            g.font = Font(mono, Font.PLAIN, 28)
            g.color = muted
            g.drawString("@${p.author.handle}", 60, titleBottom + 55)

            // Statistiques : libellé / valeur, une ligne par info.
            val kind = when (s.kind) {
                "factorio" -> "plan factorio"
                "markdown" -> "markdown"
                "text" -> "texte"
                else -> s.language?.lowercase() ?: "code"
            }
            val rows = buildList {
                add("type" to kind)
                add("vues" to s.views.toString())
                add("versions" to s.revision.toString())
                add("taille" to size(s.size))
                add("modifié" to dateFormat.format(Instant.ofEpochMilli(s.updatedAt)))
                if (s.hasPassword) add("accès" to "mot de passe")
                if (s.burnAfterViews != null) add("à usage unique" to "après ${s.burnAfterViews} vue${if (s.burnAfterViews > 1) "s" else ""}")
                if (s.expiresAt != null) add("expire" to dateFormat.format(Instant.ofEpochMilli(s.expiresAt)))
            }
            val top = titleBottom + 110
            val available = 540 - top
            val rowHeight = minOf(46, available / rows.size.coerceAtLeast(1))
            g.font = Font(mono, Font.PLAIN, 26)
            rows.forEachIndexed { i, (label, value) ->
                val y = top + i * rowHeight
                g.color = line
                g.fillRect(60, y + 12, w - 120, 1)
                g.color = muted
                g.drawString(label, 60, y)
                g.color = text
                g.drawString(ellipsize(g, value, 700), 330, y)
            }

            // Tags : pastilles à bordure fine, comme dans l'interface.
            if (s.tags.isNotEmpty()) {
                g.font = Font(mono, Font.PLAIN, 24)
                var x = 60
                for (t in s.tags.take(6)) {
                    val label = ellipsize(g, t.name, 200)
                    val tw = g.fontMetrics.stringWidth(label) + 28
                    if (x + tw > w - 60) break
                    g.color = panel
                    g.fillRect(x, 570, tw, 40)
                    g.color = line
                    g.drawRect(x, 570, tw, 40)
                    g.color = text
                    g.drawString(label, x + 14, 598)
                    x += tw + 12
                }
            }
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray().also { cache[cacheKey] = it }
    }

    private fun size(bytes: Int) = when {
        bytes < 1024 -> "$bytes o"
        bytes < 1024 * 1024 -> "%.1f Ko".format(bytes / 1024.0)
        else -> "%.1f Mo".format(bytes / 1024.0 / 1024.0)
    }

    private fun wrap(g: Graphics2D, s: String, maxWidth: Int, maxLines: Int): List<String> {
        val fm = g.fontMetrics
        val lines = mutableListOf<String>()
        var current = ""
        for (word in s.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (fm.stringWidth(candidate) <= maxWidth) { current = candidate; continue }
            if (current.isNotEmpty()) lines += current
            current = word
        }
        if (current.isNotEmpty()) lines += current
        if (lines.size > maxLines) {
            val kept = lines.take(maxLines).toMutableList()
            kept[maxLines - 1] = ellipsize(g, lines.drop(maxLines - 1).joinToString(" "), maxWidth)
            return kept
        }
        return lines.map { ellipsize(g, it, maxWidth) }
    }

    private fun ellipsize(g: Graphics2D, s: String, maxWidth: Int): String {
        val fm = g.fontMetrics
        if (fm.stringWidth(s) <= maxWidth) return s
        var end = s.length
        while (end > 0 && fm.stringWidth(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }
}
