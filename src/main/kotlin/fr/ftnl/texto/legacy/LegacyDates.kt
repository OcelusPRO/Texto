package fr.ftnl.texto.legacy

import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.db.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.*
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.time.Instant

/**
 * Restaure les vraies dates des posts importés de la v1, après coup.
 *
 * Sources (on garde la plus ancienne, jamais une date plus récente) :
 * - la date de création dans l'ancienne table `legacy_*` (conservée à l'import) ;
 * - la date du fichier `pages/<md5(clé)>` : écrit une seule fois à la création du post, c'est la date réelle.
 *
 * Dans la v1, les posts ne pouvaient pas être modifiés, mais leur `updated_at` bougeait à chaque vue :
 * pour un post importé jamais modifié depuis (version 1), « modifié le » est donc ramené à la date de création.
 * Idempotent ; exécuté une seule fois automatiquement (marqueur), ou à la demande de l'administration.
 */
class LegacyDates(private val cfg: AppConfig) {
    private val log = LoggerFactory.getLogger(LegacyDates::class.java)

    data class Report(val postsFixed: Int, val usersFixed: Int, val sources: String)

    fun restoreIfNeeded(): Report? = dbBlocking {
        if (AppMeta.selectAll().where { AppMeta.key eq META_KEY }.any()) return@dbBlocking null
        val report = restoreTx() ?: return@dbBlocking null
        AppMeta.insert { it[key] = META_KEY; it[value] = "done ${Instant.now()} $report" }
        log.info("Dates des posts v1 restaurées : {}", report)
        report
    }

    fun restore(): Report? = dbBlocking { restoreTx() }

    private fun restoreTx(): Report? {
        val conn = org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.current().connection.connection as Connection
        val table = findLegacyTable(conn)
        val dir = File(cfg.legacyPagesDir).takeIf { it.isDirectory }
        if (table == null && dir == null) return null

        val legacyDates = mutableMapOf<String, Instant>()
        if (table != null) {
            conn.createStatement().use { st ->
                st.executeQuery("SELECT key_hash, created_at FROM \"$table\" WHERE created_at IS NOT NULL").use { rs ->
                    while (rs.next()) rs.getTimestamp(2)?.toInstant()?.let { legacyDates[rs.getString(1)] = it }
                }
            }
        }

        var postsFixed = 0
        val earliestByUser = mutableMapOf<Long, Instant>()
        Posts.selectAll().where { Posts.legacy eq true }.toList().forEach { row ->
            val key = row[Posts.key]
            val current = row[Posts.createdAt]
            val fileTime = dir?.let { File(it, LegacyImporter.md5(key)) }?.takeIf(File::isFile)?.lastModified()?.let(Instant::ofEpochMilli)
            val best = listOfNotNull(current, legacyDates[key], fileTime).min()
            earliestByUser.merge(row[Posts.user].value, best) { a, b -> if (a < b) a else b }
            val unedited = row[Posts.revision] == 1
            val needsCreated = best < current
            val needsUpdated = unedited && row[Posts.updatedAt] != best
            if (!needsCreated && !needsUpdated) return@forEach
            Posts.update({ Posts.id eq row[Posts.id] }) {
                if (needsCreated) it[createdAt] = best
                if (needsUpdated) it[updatedAt] = best
            }
            if (needsCreated) PostRevisions.update({ (PostRevisions.post eq row[Posts.id]) and (PostRevisions.number eq 1) }) { it[createdAt] = best }
            postsFixed++
        }

        var usersFixed = 0
        earliestByUser.forEach { (userId, earliest) ->
            val user = Users.selectAll().where { (Users.id eq userId) and (Users.legacyId.isNotNull()) }.singleOrNull() ?: return@forEach
            if (earliest < user[Users.createdAt]) {
                Users.update({ Users.id eq userId }) { it[createdAt] = earliest }
                usersFixed++
            }
        }
        val sources = listOfNotNull(if (table != null) "table $table" else null, if (dir != null) "fichiers ${dir.path}" else null).joinToString(" + ")
        return Report(postsFixed, usersFixed, sources)
    }

    private fun findLegacyTable(conn: Connection): String? =
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() " +
                    "AND lower(table_name) LIKE 'legacy\\_%texto\\_txt' LIMIT 1"
            ).use { rs -> if (rs.next()) rs.getString(1) else null }
        }?.takeIf { table ->
            conn.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? AND column_name IN ('key_hash') LIMIT 1"
            ).use { ps -> ps.setString(1, table); ps.executeQuery().use { it.next() } }
        }

    companion object {
        const val META_KEY = "legacy_dates_restored_v1"
    }
}
