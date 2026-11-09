package fr.ftnl.texto.service

import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.db.*
import fr.ftnl.texto.util.*
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant

data class Viewer(val userId: Long?, val isAdmin: Boolean = false)

data class PostQuery(
    val q: String? = null,
    val tags: List<String> = emptyList(),
    /** null : tous, -1 : sans dossier, sinon id du dossier. */
    val folderId: Long? = null,
    val recursive: Boolean = false,
    val kind: String? = null,
    val language: String? = null,
    val visibility: String? = null,
    val sort: String = "updated",
    val page: Int = 1,
    val pageSize: Int = 30,
)

class PostService(private val cfg: AppConfig) {

    companion object {
        val KINDS = setOf("code", "text", "markdown", "factorio")
        val VISIBILITIES = setOf("public", "unlisted", "private")
        const val MAX_REVISIONS = 200
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }

    // ---------------------------------------------------------------- écriture

    fun create(userId: Long, input: PostInput): PostSummaryDto = dbBlocking {
        val content = input.content ?: badRequest("content_required")
        validateContent(content)
        val kind = input.kind?.takeIf { it in KINDS } ?: detectKind(content)
        val title = input.title?.trim()?.take(200)?.ifBlank { null } ?: defaultTitle(kind)
        val now = Instant.now()
        val key = generateKey()
        val folder = input.folderId?.let { requireFolder(userId, it) }
        val postId = Posts.insertAndGetId {
            it[Posts.key] = key
            it[user] = userId
            it[Posts.folder] = folder
            it[Posts.title] = title
            it[description] = input.description?.take(10_000) ?: ""
            it[Posts.kind] = kind
            it[language] = input.language?.take(40)?.ifBlank { null }
            it[Posts.content] = content
            it[visibility] = input.visibility?.takeIf { v -> v in VISIBILITIES } ?: "public"
            it[passwordHash] = input.password?.takeIf(String::isNotEmpty)?.let(Crypto::hashPassword)
            it[burnAfterViews] = input.burnAfterViews?.takeIf { v -> v > 0 }
            it[expiresAt] = input.expiresIn?.takeIf { s -> s > 0 }?.let { s -> now.plusSeconds(s) }
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        insertRevision(postId, 1, title, input.description ?: "", kind, input.language, content, now)
        setTags(userId, postId, withVersionTag(kind, content, input.tags.orEmpty()))
        summaries(listOf(postId)).single()
    }

    /**
     * Les tags « factorio/x.y » sont automatiques : ceux fournis par l'utilisateur sont ignorés et le tag de la version
     * du plan actuel est ajouté (donc retiré dès que le plan n'est plus de cette version).
     */
    private fun withVersionTag(kind: String, content: String, requested: List<String>): List<String> {
        val auto = if (kind == "factorio") FactorioVersion.tagFor(content) else null
        return listOfNotNull(auto) + requested.filterNot(TagService::isVersionTag)
    }

    /** Remet les tags de version de tous les posts Factorio d'aplomb. Retourne le nombre de posts modifiés. */
    fun backfillFactorioTags(): Int = dbBlocking {
        var touched = 0
        Posts.select(Posts.id, Posts.user, Posts.kind, Posts.content).where { Posts.kind eq "factorio" }.toList().forEach { row ->
            val postId = row[Posts.id].value
            val current = tagNamesOf(postId)
            val wanted = withVersionTag(row[Posts.kind], row[Posts.content], current)
            if (wanted.map(String::lowercase).toSet() != current.map(String::lowercase).toSet()) {
                setTags(row[Posts.user].value, postId, wanted)
                touched++
            }
        }
        touched
    }

    /**
     * Rattrapage unique : supprime les versions qui ne changent pas le contenu (créées avant que seul le contenu en crée)
     * et renumérote. L'historique d'un post modifié sans changement de contenu redevient « v1 ». Retourne le nombre supprimé.
     */
    fun collapseRedundantRevisionsOnce(): Int? {
        val done = dbBlocking { AppMeta.selectAll().where { AppMeta.key eq "dedupe_revisions_v1" }.any() }
        if (done) return null
        val removed = collapseRedundantRevisions()
        dbBlocking { AppMeta.insert { it[key] = "dedupe_revisions_v1"; it[value] = removed.toString() } }
        return removed
    }

    fun collapseRedundantRevisions(): Int = dbBlocking {
        var removed = 0
        val postIds = PostRevisions.select(PostRevisions.post).withDistinct().map { it[PostRevisions.post].value }
        for (postId in postIds) {
            val revs = PostRevisions.selectAll().where { PostRevisions.post eq postId }.orderBy(PostRevisions.number).toList()
            val kept = mutableListOf<ResultRow>()
            for (r in revs) {
                if (kept.isNotEmpty() && kept.last()[PostRevisions.content].trimEnd() == r[PostRevisions.content].trimEnd()) {
                    PostRevisions.deleteWhere { PostRevisions.id eq r[PostRevisions.id] }
                    removed++
                } else kept += r
            }
            if (kept.size == revs.size) continue
            kept.forEachIndexed { i, r ->
                if (r[PostRevisions.number] != i + 1) PostRevisions.update({ PostRevisions.id eq r[PostRevisions.id] }) { it[number] = i + 1 }
            }
            Posts.update({ Posts.id eq postId }) { it[revision] = kept.size }
        }
        removed
    }

    /**
     * Rattrapage unique : un post modifié sans changement de contenu avait vu sa date de modification passer à « maintenant ».
     * Elle revient à la date de la dernière version (celle du dernier vrai changement de contenu).
     */
    fun restoreUpdatedAtOnce(): Int? {
        val done = dbBlocking { AppMeta.selectAll().where { AppMeta.key eq "restore_updated_at_v1" }.any() }
        if (done) return null
        val n = restoreUpdatedAt()
        dbBlocking { AppMeta.insert { it[key] = "restore_updated_at_v1"; it[value] = n.toString() } }
        return n
    }

    fun restoreUpdatedAt(): Int = dbBlocking {
        var n = 0
        Posts.select(Posts.id, Posts.updatedAt, Posts.revision).toList().forEach { row ->
            val postId = row[Posts.id].value
            val last = PostRevisions.select(PostRevisions.createdAt).where { PostRevisions.post eq postId }
                .orderBy(PostRevisions.number, SortOrder.DESC).limit(1).singleOrNull()?.get(PostRevisions.createdAt) ?: return@forEach
            if (row[Posts.updatedAt].isAfter(last)) {
                Posts.update({ Posts.id eq postId }) { it[updatedAt] = last }
                n++
            }
        }
        n
    }

    /** Rattrapage unique : retire des posts les tags racine redondants quand un de leurs sous-tags est déjà présent. */
    fun pruneRedundantTagsOnce(): Int? {
        val done = dbBlocking { AppMeta.selectAll().where { AppMeta.key eq "prune_ancestor_tags_v1" }.any() }
        if (done) return null
        val n = pruneRedundantTags()
        dbBlocking { AppMeta.insert { it[key] = "prune_ancestor_tags_v1"; it[value] = n.toString() } }
        return n
    }

    fun pruneRedundantTags(): Int = dbBlocking {
        var n = 0
        Posts.select(Posts.id, Posts.user).toList().forEach { row ->
            val postId = row[Posts.id].value
            val current = tagNamesOf(postId)
            val lower = current.map(String::lowercase)
            if (lower.any { o -> lower.any { x -> o.startsWith(x + "/") } }) {
                setTags(row[Posts.user].value, postId, current)
                n++
            }
        }
        n
    }

    /** Rattrapage unique au démarrage ; null si déjà fait. */
    fun backfillFactorioTagsOnce(): Int? {
        val done = dbBlocking { AppMeta.selectAll().where { AppMeta.key eq "factorio_version_tags_v2" }.any() }
        if (done) return null
        val n = backfillFactorioTags()
        dbBlocking { AppMeta.insert { it[key] = "factorio_version_tags_v2"; it[value] = n.toString() } }
        return n
    }

    private fun tagNamesOf(postId: Long): List<String> =
        (PostTags innerJoin Tags).select(Tags.name).where { PostTags.post eq postId }.map { it[Tags.name] }

    fun update(viewer: Viewer, key: String, input: PostInput): PostSummaryDto = dbBlocking {
        val row = findRow(key) ?: notFound()
        val userId = row[Posts.user].value
        if (viewer.userId != userId) forbidden()
        val postId = row[Posts.id].value
        input.content?.let(::validateContent)
        val now = Instant.now()

        val newTitle = input.title?.trim()?.take(200)?.ifBlank { null } ?: row[Posts.title]
        val newDesc = input.description?.take(10_000) ?: row[Posts.description]
        val newKind = input.kind?.takeIf { it in KINDS } ?: row[Posts.kind]
        val newLang = if (input.language != null) input.language.take(40).ifBlank { null } else row[Posts.language]
        val newContent = input.content ?: row[Posts.content]
        // Une version n'est créée que si le contenu change (les espaces de fin ne comptent pas) : titre, description,
        // type, langage, tags, dossier… se modifient sans toucher à l'historique.
        val contentChanged = newContent.trimEnd() != row[Posts.content].trimEnd()
        val changed = contentChanged || newTitle != row[Posts.title] || newDesc != row[Posts.description] ||
            newKind != row[Posts.kind] || newLang != row[Posts.language] || newContent != row[Posts.content]
        val newRevision = if (contentChanged) row[Posts.revision] + 1 else row[Posts.revision]

        val folder = when {
            input.clearFolder -> null
            input.folderId != null -> requireFolder(userId, input.folderId)
            else -> row[Posts.folder]?.value
        }

        Posts.update({ Posts.id eq postId }) {
            it[title] = newTitle
            it[description] = newDesc
            it[kind] = newKind
            it[language] = newLang
            it[content] = newContent
            it[revision] = newRevision
            it[Posts.folder] = folder
            input.visibility?.takeIf { v -> v in VISIBILITIES }?.let { v -> it[visibility] = v }
            input.password?.let { p -> it[passwordHash] = p.takeIf(String::isNotEmpty)?.let(Crypto::hashPassword) }
            input.burnAfterViews?.let { b -> it[burnAfterViews] = b.takeIf { v -> v > 0 } }
            input.expiresIn?.let { s -> it[expiresAt] = s.takeIf { v -> v > 0 }?.let { v -> now.plusSeconds(v) } }
            // La date « modifié » suit le contenu, pas les métadonnées.
            if (contentChanged) {
                it[updatedAt] = now
                it[hasThumbnail] = false
            }
        }
        if (contentChanged) {
            insertRevision(postId, newRevision, newTitle, newDesc, newKind, newLang, newContent, now)
            pruneRevisions(postId)
            // La miniature (rendu FBE) ne correspond plus au contenu : elle sera régénérée par l'auteur.
            PostThumbnails.deleteWhere { PostThumbnails.post eq postId }
        } else if (changed) {
            // Métadonnées seules : la version courante suit (titre, description…), sans nouvelle version.
            PostRevisions.update({ (PostRevisions.post eq postId) and (PostRevisions.number eq newRevision) }) {
                it[PostRevisions.title] = newTitle
                it[PostRevisions.description] = newDesc
                it[PostRevisions.kind] = newKind
                it[PostRevisions.language] = newLang
                it[PostRevisions.content] = newContent
            }
        }
        // Tags de version automatiques : recalculés à chaque enregistrement, jamais modifiables à la main.
        setTags(userId, postId, withVersionTag(newKind, newContent, input.tags ?: tagNamesOf(postId)))
        summaries(listOf(postId)).single()
    }

    fun delete(viewer: Viewer, key: String) = dbBlocking {
        val row = findRow(key) ?: notFound()
        if (viewer.userId != row[Posts.user].value && !viewer.isAdmin) forbidden()
        Posts.deleteWhere { Posts.id eq row[Posts.id] }
    }

    fun setThumbnail(viewer: Viewer, key: String, png: ByteArray) = dbBlocking {
        val row = findRow(key) ?: notFound()
        if (viewer.userId != row[Posts.user].value) forbidden()
        if (png.size > 3_000_000) throw ApiException(HttpStatusCode.PayloadTooLarge, "too_large")
        if (png.size < 8 || !png.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) badRequest("not_png")
        val postId = row[Posts.id].value
        PostThumbnails.deleteWhere { PostThumbnails.post eq postId }
        PostThumbnails.insert {
            it[post] = postId
            it[PostThumbnails.png] = png
            it[updatedAt] = Instant.now()
        }
        Posts.update({ Posts.id eq postId }) { it[hasThumbnail] = true }
    }

    fun thumbnail(key: String): ByteArray? = dbBlocking {
        val row = findRow(key) ?: return@dbBlocking null
        if (row[Posts.visibility] == "private" || row[Posts.passwordHash] != null || row[Posts.burnAfterViews] != null) return@dbBlocking null
        PostThumbnails.selectAll().where { PostThumbnails.post eq row[Posts.id] }.singleOrNull()?.get(PostThumbnails.png)
    }

    // ---------------------------------------------------------------- lecture

    sealed interface ReadResult {
        data class Ok(val post: PostDto) : ReadResult
        data object PasswordRequired : ReadResult
        data object WrongPassword : ReadResult
    }

    /**
     * Lit un post. [countView] : la lecture compte comme une vue (pas un bot, pas déjà vu).
     * Les posts expirés sont supprimés à la lecture ; les posts « brûler après lecture » aussi,
     * une fois leur nombre de vues atteint.
     */
    fun read(viewer: Viewer, key: String, password: String?, countView: Boolean): ReadResult = dbBlocking {
        val row = findRow(key) ?: notFound()
        val postId = row[Posts.id].value
        val now = Instant.now()
        if (row[Posts.expiresAt]?.isBefore(now) == true) {
            Posts.deleteWhere { Posts.id eq postId }
            notFound()
        }
        val isOwner = viewer.userId == row[Posts.user].value
        if (row[Posts.visibility] == "private" && !isOwner && !viewer.isAdmin) notFound()
        val hash = row[Posts.passwordHash]
        if (hash != null && !isOwner && !viewer.isAdmin) {
            if (password.isNullOrEmpty()) return@dbBlocking ReadResult.PasswordRequired
            if (!Crypto.verifyPassword(password, hash)) return@dbBlocking ReadResult.WrongPassword
        }
        var burned = false
        if (countView && !isOwner) {
            val views = row[Posts.views] + 1
            val burnAt = row[Posts.burnAfterViews]
            if (burnAt != null && views >= burnAt) {
                burned = true
            } else {
                Posts.update({ Posts.id eq postId }) { it[Posts.views] = views }
            }
        }
        val summary = summaries(listOf(postId)).single()
        val author = Users.selectAll().where { Users.id eq row[Posts.user] }.single()
        val dto = PostDto(
            summary = if (burned) summary.copy(views = summary.views + 1) else summary,
            content = row[Posts.content],
            author = UserMini(author[Users.handle], author[Users.displayName], author[Users.avatarUrl]),
            isOwner = isOwner,
            burned = burned,
        )
        if (burned) Posts.deleteWhere { Posts.id eq postId }
        ReadResult.Ok(dto)
    }

    /** Métadonnées publiques pour les aperçus (OpenGraph, Discord). Null si le post n'est pas prévisualisable. */
    data class PreviewInfo(
        val summary: PostSummaryDto,
        val author: UserMini,
    )

    fun preview(key: String): PreviewInfo? = dbBlocking {
        val row = findRow(key) ?: return@dbBlocking null
        if (row[Posts.expiresAt]?.isBefore(Instant.now()) == true) return@dbBlocking null
        if (row[Posts.visibility] == "private") return@dbBlocking null
        val summary = summaries(listOf(row[Posts.id].value)).single()
        val author = Users.selectAll().where { Users.id eq row[Posts.user] }.single()
        // Jamais d'extrait du contenu dans un aperçu : seulement des statistiques.
        PreviewInfo(
            summary = summary,
            author = UserMini(author[Users.handle], author[Users.displayName], author[Users.avatarUrl]),
        )
    }

    fun revisions(viewer: Viewer, key: String): List<RevisionSummaryDto> = dbBlocking {
        val row = readableRow(viewer, key)
        PostRevisions.select(PostRevisions.number, PostRevisions.title, PostRevisions.content.charLength(), PostRevisions.createdAt)
            .where { PostRevisions.post eq row[Posts.id] }
            .orderBy(PostRevisions.number, SortOrder.DESC)
            .map {
                RevisionSummaryDto(
                    it[PostRevisions.number], it[PostRevisions.title],
                    it[PostRevisions.content.charLength()] ?: 0, it[PostRevisions.createdAt].toEpochMilli(),
                )
            }
    }

    fun revision(viewer: Viewer, key: String, number: Int): RevisionDto = dbBlocking {
        val row = readableRow(viewer, key)
        val r = PostRevisions.selectAll()
            .where { (PostRevisions.post eq row[Posts.id]) and (PostRevisions.number eq number) }
            .singleOrNull() ?: notFound()
        RevisionDto(
            r[PostRevisions.number], r[PostRevisions.title], r[PostRevisions.description], r[PostRevisions.kind],
            r[PostRevisions.language], r[PostRevisions.content], r[PostRevisions.createdAt].toEpochMilli(),
        )
    }

    fun restore(viewer: Viewer, key: String, number: Int): PostSummaryDto {
        val rev = revision(viewer, key, number)
        return update(viewer, key, PostInput(rev.title, rev.description, rev.kind, rev.language ?: "", rev.content))
    }

    /** Les révisions ne sont visibles que par l'auteur (elles peuvent contenir des données retirées depuis). */
    private fun readableRow(viewer: Viewer, key: String): ResultRow {
        val row = findRow(key) ?: notFound()
        if (viewer.userId != row[Posts.user].value) notFound()
        return row
    }

    fun raw(viewer: Viewer, key: String, password: String?): String =
        when (val r = read(viewer, key, password, countView = false)) {
            is ReadResult.Ok -> r.post.content
            ReadResult.PasswordRequired, ReadResult.WrongPassword -> unauthorized("password_required")
        }

    // ---------------------------------------------------------------- listes

    fun listOwn(userId: Long, query: PostQuery): PageDto<PostSummaryDto> = dbBlocking {
        var cond: Op<Boolean> = Posts.user eq userId
        query.q?.trim()?.takeIf(String::isNotEmpty)?.let { cond = cond and searchOp(it) }
        query.kind?.takeIf { it in KINDS }?.let { cond = cond and (Posts.kind eq it) }
        query.language?.takeIf(String::isNotBlank)?.let { cond = cond and (Posts.language eq it) }
        query.visibility?.takeIf { it in VISIBILITIES }?.let { cond = cond and (Posts.visibility eq it) }
        when (val f = query.folderId) {
            null -> {}
            -1L -> cond = cond and Posts.folder.isNull()
            else -> {
                val ids = if (query.recursive) descendants(userId, f) else listOf(f)
                cond = cond and (Posts.folder inList ids)
            }
        }
        if (query.tags.isNotEmpty()) {
            // Un tag racine inclut ses sous-tags (factorio => factorio/1.0, factorio/2.0…).
            for (name in query.tags.distinct()) {
                val ids = TagService.subtreeIds(userId, name)
                if (ids.isEmpty()) return@dbBlocking PageDto(emptyList(), 0, query.page, query.pageSize)
                cond = cond and (Posts.id inSubQuery PostTags.select(PostTags.post).where { PostTags.tag inList ids })
            }
        }
        page(cond, query, withAuthor = false)
    }

    /**
     * Posts publics d'un profil.
     * - avec une recherche [q] : tous les posts publics, dans tous les dossiers ;
     * - sinon, à la racine (posts sans dossier) ou, si [folderId] est donné, directement dans ce dossier.
     *   Un dossier sans contenu public est introuvable.
     */
    fun listPublicOf(
        handle: String, page: Int, pageSize: Int, q: String?, folderId: Long? = null,
        kind: String? = null, tags: List<String> = emptyList(), sort: String = "updated",
    ): PageDto<PostSummaryDto> = dbBlocking {
        val user = userRowByHandleOrAlias(handle) ?: notFound()
        if (user[Users.banned]) notFound()
        var cond: Op<Boolean> = (Posts.user eq user[Users.id]) and (Posts.visibility eq "public")
        val search = q?.trim()?.takeIf(String::isNotEmpty)
        val kindFilter = kind?.takeIf { it in KINDS }
        val tagNames = tags.map(String::trim).filter(String::isNotEmpty).map(String::lowercase).distinct()
        kindFilter?.let { cond = cond and (Posts.kind eq it) }
        if (tagNames.isNotEmpty()) {
            for (name in tagNames) {
                val ids = TagService.subtreeIds(user[Users.id].value, name)
                if (ids.isEmpty()) return@dbBlocking PageDto(emptyList(), 0, page, pageSize)
                cond = cond and (Posts.id inSubQuery PostTags.select(PostTags.post).where { PostTags.tag inList ids })
            }
        }
        // Recherche ou filtre actif : on cherche dans tous les dossiers.
        if (search != null || kindFilter != null || tagNames.isNotEmpty()) {
            search?.let { cond = cond and searchOp(it) }
        } else if (folderId != null) {
            if (FolderService.publicTreeTx(user[Users.id].value).none { it.id == folderId }) notFound()
            cond = cond and (Posts.folder eq folderId)
        } else {
            cond = cond and Posts.folder.isNull()
        }
        page(cond, PostQuery(page = page, pageSize = pageSize, sort = sort), withAuthor = false)
    }

    fun listAll(q: String?, page: Int, pageSize: Int): PageDto<PostSummaryDto> = dbBlocking {
        var cond: Op<Boolean> = Op.TRUE
        q?.trim()?.takeIf(String::isNotEmpty)?.let { cond = searchOp(it) or (Posts.key eq it) }
        page(cond, PostQuery(page = page, pageSize = pageSize), withAuthor = true)
    }

    private fun page(cond: Op<Boolean>, query: PostQuery, withAuthor: Boolean): PageDto<PostSummaryDto> {
        val pageSize = query.pageSize.coerceIn(1, 100)
        val page = query.page.coerceAtLeast(1)
        val total = Posts.selectAll().where { cond }.count()
        val order = when (query.sort) {
            "created" -> Posts.createdAt to SortOrder.DESC
            "title" -> Posts.title to SortOrder.ASC
            "views" -> Posts.views to SortOrder.DESC
            else -> Posts.updatedAt to SortOrder.DESC
        }
        val ids = Posts.select(Posts.id).where { cond }
            .orderBy(order.first, order.second)
            .limit(pageSize).offset(((page - 1) * pageSize).toLong())
            .map { it[Posts.id].value }
        return PageDto(summaries(ids, withAuthor), total, page, pageSize)
    }

    /** Recherche plein texte (préfixes) + correspondance partielle sur le titre. */
    private fun searchOp(q: String): Op<Boolean> {
        val words = q.split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\p{L}\\p{N}_-]"), "") }
            .filter(String::isNotEmpty)
            .take(8)
        val title = Posts.title.lowerCase() like "%${q.lowercase().replace("%", "").replace("_", "\\_")}%"
        if (words.isEmpty()) return title
        val tsQuery = words.joinToString(" & ") { "$it:*" }
        val fts = object : Op<Boolean>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                queryBuilder.append("t_posts.search @@ to_tsquery('simple', ")
                queryBuilder.registerArgument(TextColumnType(), tsQuery)
                queryBuilder.append(")")
            }
        }
        return fts or title
    }

    /** Résumés (avec tags) dans l'ordre des [ids]. */
    fun summaries(ids: List<Long>, withAuthor: Boolean = false): List<PostSummaryDto> {
        if (ids.isEmpty()) return emptyList()
        val tagsByPost = (PostTags innerJoin Tags).selectAll().where { PostTags.post inList ids }
            .groupBy({ it[PostTags.post].value }) { TagDto(it[Tags.id].value, it[Tags.name], it[Tags.color]) }
        val size = Posts.content.charLength()
        val columns = Posts.columns.filter { it != Posts.content } + size
        val authors = if (withAuthor) {
            val userIds = Posts.select(Posts.user).where { Posts.id inList ids }.map { it[Posts.user].value }.distinct()
            Users.selectAll().where { Users.id inList userIds }
                .associate { it[Users.id].value to UserMini(it[Users.handle], it[Users.displayName], it[Users.avatarUrl]) }
        } else emptyMap()
        val rows = Posts.select(columns).where { Posts.id inList ids }.associateBy { it[Posts.id].value }
        return ids.mapNotNull { id ->
            val r = rows[id] ?: return@mapNotNull null
            PostSummaryDto(
                key = r[Posts.key],
                title = r[Posts.title],
                description = r[Posts.description],
                kind = r[Posts.kind],
                language = r[Posts.language],
                visibility = r[Posts.visibility],
                views = r[Posts.views],
                revision = r[Posts.revision],
                tags = tagsByPost[id].orEmpty().sortedBy { it.name.lowercase() },
                folderId = r[Posts.folder]?.value,
                hasPassword = r[Posts.passwordHash] != null,
                burnAfterViews = r[Posts.burnAfterViews],
                hasThumbnail = r[Posts.hasThumbnail],
                size = r[size] ?: 0,
                createdAt = r[Posts.createdAt].toEpochMilli(),
                updatedAt = r[Posts.updatedAt].toEpochMilli(),
                expiresAt = r[Posts.expiresAt]?.toEpochMilli(),
                author = authors[r[Posts.user].value],
            )
        }
    }

    fun purgeExpired(): Int = dbBlocking {
        val now = Instant.now()
        Posts.deleteWhere { Posts.expiresAt.isNotNull() and (Posts.expiresAt less now) }
    }

    // ---------------------------------------------------------------- helpers

    private fun findRow(key: String): ResultRow? =
        Posts.selectAll().where { Posts.key eq key }.singleOrNull()

    private fun validateContent(content: String) {
        if (content.isBlank()) badRequest("content_required")
        if (content.length > cfg.maxContentLength) throw ApiException(HttpStatusCode.PayloadTooLarge, "too_large")
    }

    private fun generateKey(): String {
        repeat(20) {
            val key = Crypto.randomKey(cfg.keyLength.coerceIn(6, 32))
            if (Posts.selectAll().where { Posts.key eq key }.empty()) return key
        }
        error("Impossible de générer une clé unique")
    }

    private fun requireFolder(userId: Long, folderId: Long): Long {
        Folders.selectAll().where { (Folders.id eq folderId) and (Folders.user eq userId) }.singleOrNull()
            ?: badRequest("invalid_folder")
        return folderId
    }

    private fun descendants(userId: Long, root: Long): List<Long> {
        val all = Folders.select(Folders.id, Folders.parent).where { Folders.user eq userId }
            .map { it[Folders.id].value to it[Folders.parent]?.value }
        val result = mutableListOf(root)
        var frontier = listOf(root)
        while (frontier.isNotEmpty()) {
            frontier = all.filter { it.second in frontier }.map { it.first }.filter { it !in result }
            result += frontier
        }
        return result
    }

    private fun insertRevision(
        postId: Long, number: Int, title: String, description: String, kind: String,
        language: String?, content: String, at: Instant,
    ) {
        PostRevisions.insert {
            it[post] = postId
            it[PostRevisions.number] = number
            it[PostRevisions.title] = title
            it[PostRevisions.description] = description
            it[PostRevisions.kind] = kind
            it[PostRevisions.language] = language?.ifBlank { null }
            it[PostRevisions.content] = content
            it[createdAt] = at
        }
    }

    private fun pruneRevisions(postId: Long) {
        val keep = PostRevisions.select(PostRevisions.id).where { PostRevisions.post eq postId }
            .orderBy(PostRevisions.number, SortOrder.DESC).limit(MAX_REVISIONS).map { it[PostRevisions.id].value }
        if (keep.size >= MAX_REVISIONS) {
            PostRevisions.deleteWhere { (PostRevisions.post eq postId) and (PostRevisions.id notInList keep) }
        }
    }

    /** Remplace les tags d'un post ; les tags inconnus sont créés pour l'utilisateur. */
    fun setTags(userId: Long, postId: Long, names: List<String>) {
        val all = names.map(TagService::normalize).filter(String::isNotEmpty).distinctBy(String::lowercase)
        // Un tag et l'un de ses sous-tags ensemble : on ne garde que le sous-tag (le filtre sur la racine l'inclut déjà).
        val clean = all.filter { n -> all.none { o -> o.lowercase().startsWith(n.lowercase() + "/") } }.take(20)
        val ids = clean.map { TagService.getOrCreate(userId, it) }
        PostTags.deleteWhere { PostTags.post eq postId }
        ids.forEach { tid -> PostTags.insert { it[post] = postId; it[tag] = tid } }
        TagService.purgeUnusedVersionTags(userId)
    }

    private fun detectKind(content: String): String {
        val t = content.trim()
        if (t.length > 20 && (t.startsWith("0e") || t.startsWith("0eN")) && !t.contains(Regex("\\s"))) return "factorio"
        return "code"
    }

    private fun defaultTitle(kind: String) = if (kind == "factorio") "Blueprint" else "Sans titre"
}
