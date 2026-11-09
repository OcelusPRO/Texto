package fr.ftnl.texto.legacy

import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.db.*
import fr.ftnl.texto.service.UserService
import fr.ftnl.texto.util.Crypto
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

/**
 * Importe les données de Texto v1 (tables TBL_* + fichiers `pages/<md5(clé)>`) dans le schéma v2.
 *
 * - Les clés des posts sont conservées : les anciens liens `/{clé}` restent valides.
 * - Les comptes v1 deviennent des utilisateurs sans compte lié ; ils sont rattachés automatiquement
 *   à la première connexion Discord/Twitch avec le même email (hash SHA3-512 conservé).
 * - Les anciennes clés API restent utilisables (stockées hachées comme tokens « legacy »).
 * - Les tables v1 sont renommées en `legacy_*` (jamais supprimées).
 *
 * L'import ne s'exécute qu'une fois (marqueur dans t_app_meta) et dans une seule transaction.
 */
class LegacyImporter(private val cfg: AppConfig, private val users: UserService) {
    private val log = LoggerFactory.getLogger(LegacyImporter::class.java)

    data class Report(
        val users: Int = 0,
        val socials: Int = 0,
        val tokens: Int = 0,
        val posts: Int = 0,
        val missingFiles: Int = 0,
        val expired: Int = 0,
    )

    private val prefix = (System.getenv("DATABASE_PREFIX") ?: "").lowercase()
    private val legacyNames = listOf("tbl_author_aut", "tbl_social_media_som", "tbl_texto_txt", "tbl_user_login_ulo")

    fun runIfNeeded(): Report? = dbBlocking {
        if (AppMeta.selectAll().where { AppMeta.key eq META_KEY }.any()) return@dbBlocking null
        val conn = connection.connection as Connection
        val tables = findTables(conn)
        if ("tbl_texto_txt" !in tables && "tbl_author_aut" !in tables) {
            AppMeta.insert { it[key] = META_KEY; it[value] = "none" }
            return@dbBlocking null
        }
        log.info("Base Texto v1 détectée ({}), import en cours…", tables.values)
        val report = import(conn, tables)
        tables.values.forEach { actual ->
            conn.createStatement().use { it.execute("ALTER TABLE ${q(actual)} RENAME TO ${q("legacy_" + actual.lowercase())}") }
        }
        AppMeta.insert { it[key] = META_KEY; it[value] = "done ${Instant.now()} $report" }
        log.info("Import v1 terminé : {}", report)
        report
    }

    /** nom en minuscules sans préfixe -> nom réel dans la base. */
    private fun findTables(conn: Connection): Map<String, String> {
        val found = mutableMapOf<String, String>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()").use { rs ->
                while (rs.next()) {
                    val name = rs.getString(1)
                    val logical = name.lowercase().removePrefix(prefix)
                    if (logical in legacyNames) found[logical] = name
                }
            }
        }
        return found
    }

    private fun columns(conn: Connection, table: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        conn.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ?").use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs -> while (rs.next()) rs.getString(1).let { result[it.lowercase()] = it } }
        }
        return result
    }

    private fun import(conn: Connection, tables: Map<String, String>): Report {
        var report = Report()
        val userIds = mutableMapOf<Int, Long>()
        val now = Instant.now()

        tables["tbl_author_aut"]?.let { t ->
            val c = columns(conn, t)
            select(conn, t, c) { rs ->
                val legacyId = rs.getInt(c.getValue("id"))
                val name = rs.getString(c.getValue("name")) ?: "user$legacyId"
                val avatar = c["avatar"]?.let(rs::getString)?.takeIf { it.startsWith("http") }
                val created = c["created_at"]?.let(rs::getTimestamp)?.toInstant() ?: now
                val id = Users.insertAndGetId {
                    it[handle] = users.uniqueHandle(name)
                    it[displayName] = name.take(100)
                    it[avatarUrl] = avatar
                    it[Users.legacyId] = legacyId
                    it[createdAt] = created
                }.value
                userIds[legacyId] = id
                report = report.copy(users = report.users + 1)
                c["api_key"]?.let(rs::getString)?.takeIf(String::isNotBlank)?.let { apiKey ->
                    ApiTokens.insert {
                        it[user] = id
                        it[ApiTokens.name] = "Clé API v1"
                        it[tokenHash] = Crypto.sha256(apiKey)
                        it[prefix] = apiKey.take(6) + "…"
                        it[scopes] = "posts:read,posts:write"
                        it[legacy] = true
                        it[createdAt] = created
                    }
                    report = report.copy(tokens = report.tokens + 1)
                }
            }
        }

        tables["tbl_social_media_som"]?.let { t ->
            val c = columns(conn, t)
            select(conn, t, c) { rs ->
                val owner = userIds[rs.getInt(c.getValue("user"))] ?: return@select
                SocialLinks.insert {
                    it[user] = owner
                    it[icon] = fr.ftnl.texto.util.SocialIcons.normalize(rs.getString(c.getValue("icon")) ?: "", rs.getString(c.getValue("url")))
                    it[url] = rs.getString(c.getValue("url")).take(512)
                }
                report = report.copy(socials = report.socials + 1)
            }
        }

        tables["tbl_texto_txt"]?.let { t ->
            val c = columns(conn, t)
            val pagesDir = File(cfg.legacyPagesDir)
            select(conn, t, c) { rs ->
                val owner = userIds[rs.getInt(c.getValue("user"))] ?: return@select
                val key = rs.getString(c.getValue("key_hash"))
                val expire = c["expire_at"]?.let { rs.getTimestamp(it) }?.toInstant()
                if (expire != null && expire.isBefore(now)) {
                    report = report.copy(expired = report.expired + 1); return@select
                }
                val file = File(pagesDir, md5(key))
                if (!file.isFile) {
                    report = report.copy(missingFiles = report.missingFiles + 1); return@select
                }
                val content = file.readText()
                val title = (rs.getString(c.getValue("name")) ?: "Sans titre").take(200)
                val description = c["description"]?.let(rs::getString) ?: ""
                val created = c["created_at"]?.let(rs::getTimestamp)?.toInstant() ?: now
                val updated = c["updated_at"]?.let(rs::getTimestamp)?.toInstant() ?: created
                val kind = if (content.trimStart().startsWith("0eN")) "factorio" else "code"
                val postId = Posts.insertAndGetId {
                    it[Posts.key] = key
                    it[user] = owner
                    it[Posts.title] = title
                    it[Posts.description] = description
                    it[Posts.kind] = kind
                    it[Posts.content] = content
                    it[visibility] = if (c["public"]?.let(rs::getBoolean) == true) "public" else "unlisted"
                    it[views] = c["vues"]?.let(rs::getInt) ?: 0
                    it[legacy] = true
                    it[expiresAt] = expire
                    it[createdAt] = created
                    it[updatedAt] = updated
                }.value
                PostRevisions.insert {
                    it[post] = postId
                    it[number] = 1
                    it[PostRevisions.title] = title
                    it[PostRevisions.description] = description
                    it[PostRevisions.kind] = kind
                    it[PostRevisions.content] = content
                    it[createdAt] = created
                }
                report = report.copy(posts = report.posts + 1)
            }
        }
        return report
    }

    private fun select(conn: Connection, table: String, cols: Map<String, String>, row: (java.sql.ResultSet) -> Unit) {
        val order = cols["id"]?.let { " ORDER BY ${q(it)}" } ?: ""
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM ${q(table)}$order").use { rs -> while (rs.next()) row(rs) }
        }
    }

    private fun q(identifier: String) = "\"" + identifier.replace("\"", "\"\"") + "\""

    companion object {
        const val META_KEY = "legacy_import_v1"

        fun md5(value: String): String =
            MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
