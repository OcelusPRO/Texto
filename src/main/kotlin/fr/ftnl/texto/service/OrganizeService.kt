package fr.ftnl.texto.service

import fr.ftnl.texto.db.*
import fr.ftnl.texto.util.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant

object TagService {
    val COLORS = listOf("slate", "red", "orange", "amber", "lime", "emerald", "cyan", "sky", "indigo", "violet", "pink", "rose")

    /**
     * Tags hiérarchiques : le nom est un chemin « factorio/1.0 ». Le tag « factorio » est la racine de « factorio/1.0 » ;
     * filtrer sur la racine inclut tous ses sous-tags. Les ancêtres sont créés automatiquement.
     */
    fun normalize(raw: String): String =
        raw.split('/').map { it.trim().removePrefix("#").trim() }.filter(String::isNotEmpty).joinToString("/").take(200).trimEnd('/')

    private val VERSION_TAG = Regex("^factorio/\\d+\\.\\d+$", RegexOption.IGNORE_CASE)

    /** Tag de version automatique (« factorio/2.0 ») : géré par Texto, ni ajouté, ni retiré, ni renommé à la main. */
    fun isVersionTag(name: String) = VERSION_TAG.matches(normalize(name))

    /** Supprime les tags de version qui ne sont plus portés par aucun post. */
    fun purgeUnusedVersionTags(userId: Long) {
        val used = (PostTags innerJoin Tags).select(Tags.id).where { Tags.user eq userId }.map { it[Tags.id].value }.toSet()
        val stale = Tags.selectAll().where { (Tags.user eq userId) and (Tags.name.lowerCase() like "factorio/%") }
            .filter { isVersionTag(it[Tags.name]) && it[Tags.id].value !in used }.map { it[Tags.id].value }
        if (stale.isNotEmpty()) Tags.deleteWhere { Tags.id inList stale }
    }

    private fun colorFor(name: String) = COLORS[(name.hashCode() and 0x7fffffff) % COLORS.size]

    /** Id du tag [name] (déjà normalisé), créé avec ses ancêtres s'il n'existe pas. */
    fun getOrCreate(userId: Long, name: String): Long {
        val parts = name.split('/')
        var id = 0L
        for (i in 1..parts.size) {
            val path = parts.subList(0, i).joinToString("/")
            id = Tags.selectAll().where { (Tags.user eq userId) and (Tags.name.lowerCase() eq path.lowercase()) }
                .singleOrNull()?.get(Tags.id)?.value
                ?: Tags.insertAndGetId { it[user] = userId; it[Tags.name] = path; it[color] = colorFor(path) }.value
        }
        return id
    }

    /** Ids du tag [name] et de tous ses sous-tags. */
    fun subtreeIds(userId: Long, name: String): List<Long> {
        val n = normalize(name).lowercase()
        if (n.isEmpty()) return emptyList()
        val esc = n.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return Tags.selectAll()
            .where { (Tags.user eq userId) and ((Tags.name.lowerCase() eq n) or (Tags.name.lowerCase() like "$esc/%")) }
            .map { it[Tags.id].value }
    }

    /** Pour chaque tag, nombre de posts distincts portant ce tag ou l'un de ses sous-tags. */
    private fun subtreeCounts(userId: Long, publicOnly: Boolean): Map<Long, Long> {
        val tags = Tags.selectAll().where { Tags.user eq userId }.map { it[Tags.id].value to it[Tags.name].lowercase() }
        var cond: Op<Boolean> = Tags.user eq userId
        if (publicOnly) cond = cond and (Posts.visibility eq "public")
        val byTag = (PostTags innerJoin Tags innerJoin Posts).select(PostTags.tag, PostTags.post).where { cond }
            .groupBy({ it[PostTags.tag].value }) { it[PostTags.post].value }
        return tags.associate { (id, name) ->
            id to tags.filter { (_, n) -> n == name || n.startsWith("$name/") }.flatMap { byTag[it.first].orEmpty() }.toSet().size.toLong()
        }
    }

    fun list(userId: Long): List<TagDto> = dbBlocking {
        val counts = subtreeCounts(userId, publicOnly = false)
        Tags.selectAll().where { Tags.user eq userId }.orderBy(Tags.name.lowerCase())
            .map { TagDto(it[Tags.id].value, it[Tags.name], it[Tags.color], counts[it[Tags.id].value] ?: 0) }
    }

    /** Tags d'un profil public : seulement ceux portés (eux ou un sous-tag) par au moins un post public. */
    fun publicList(handle: String): List<TagDto> = dbBlocking {
        val user = userRowByHandleOrAlias(handle) ?: notFound()
        if (user[Users.banned]) notFound()
        val uid = user[Users.id].value
        val counts = subtreeCounts(uid, publicOnly = true)
        Tags.selectAll().where { Tags.user eq uid }.orderBy(Tags.name.lowerCase())
            .filter { (counts[it[Tags.id].value] ?: 0) > 0 }
            .map { TagDto(it[Tags.id].value, it[Tags.name], it[Tags.color], counts[it[Tags.id].value] ?: 0) }
    }

    fun create(userId: Long, input: TagInput): TagDto = dbBlocking {
        val name = normalize(input.name).ifEmpty { badRequest("name_required") }
        if (isVersionTag(name)) badRequest("tag_reserved")
        if (Tags.selectAll().where { (Tags.user eq userId) and (Tags.name.lowerCase() eq name.lowercase()) }.any()) conflict("tag_exists")
        val id = getOrCreate(userId, name)
        val color = input.color?.takeIf { it in COLORS }
        if (color != null) Tags.update({ Tags.id eq id }) { it[Tags.color] = color }
        val row = Tags.selectAll().where { Tags.id eq id }.single()
        TagDto(id, row[Tags.name], row[Tags.color])
    }

    fun update(userId: Long, id: Long, input: TagInput): TagDto = dbBlocking {
        val row = Tags.selectAll().where { (Tags.id eq id) and (Tags.user eq userId) }.singleOrNull() ?: notFound()
        val name = normalize(input.name).ifEmpty { row[Tags.name] }
        val old = row[Tags.name]
        val moving = subtreeIds(userId, old).filter { it != id }
            .map { cid -> cid to Tags.selectAll().where { Tags.id eq cid }.single()[Tags.name] }
        // Les tags de version sont gérés par Texto : on ne les renomme pas, ni via leur racine, et on n'en crée pas par renommage.
        if (isVersionTag(old) || isVersionTag(name) || moving.any { isVersionTag(it.second) }) badRequest("tag_reserved")
        val renamed = listOf(id to name) + moving.map { (cid, cn) -> cid to name + cn.substring(old.length) }
        val moved = renamed.map { it.first }.toSet()
        for ((_, n) in renamed) {
            val clash = Tags.selectAll().where { (Tags.user eq userId) and (Tags.name.lowerCase() eq n.lowercase()) }.any { it[Tags.id].value !in moved }
            if (clash) conflict("tag_exists")
        }
        val color = input.color?.takeIf { it in COLORS } ?: row[Tags.color]
        // Renommer une racine renomme aussi ses sous-tags (le préfixe suit).
        renamed.forEach { (cid, n) -> Tags.update({ Tags.id eq cid }) { it[Tags.name] = n } }
        Tags.update({ Tags.id eq id }) { it[Tags.color] = color }
        getOrCreate(userId, name)
        TagDto(id, name, color)
    }

    fun delete(userId: Long, id: Long) = dbBlocking {
        Tags.selectAll().where { (Tags.id eq id) and (Tags.user eq userId) }.singleOrNull()?.let { if (isVersionTag(it[Tags.name])) badRequest("tag_reserved") }
        if (Tags.deleteWhere { (Tags.id eq id) and (Tags.user eq userId) } == 0) notFound()
    }
}

object FolderService {
    /**
     * Arbre des dossiers d'un profil public. Les dossiers sont privés par défaut : on n'expose que ceux qui contiennent
     * au moins un post public, directement ou dans un sous-dossier ; les autres (et leur nom) restent invisibles.
     */
    fun publicTreeTx(userId: Long): List<PublicFolderDto> {
        val folders = Folders.selectAll().where { Folders.user eq userId }.toList()
        val count = Posts.id.count()
        val direct = Posts.select(Posts.folder, count)
            .where { (Posts.user eq userId) and (Posts.visibility eq "public") and Posts.folder.isNotNull() }
            .groupBy(Posts.folder).associate { it[Posts.folder]!!.value to it[count] }
        val children = folders.groupBy({ it[Folders.parent]?.value }) { it[Folders.id].value }
        fun total(id: Long): Long = (direct[id] ?: 0) + children[id].orEmpty().sumOf { total(it) }
        return folders.mapNotNull { f ->
            val id = f[Folders.id].value
            val t = total(id)
            if (t == 0L) null else PublicFolderDto(id, f[Folders.parent]?.value, f[Folders.name], t, direct[id] ?: 0)
        }.sortedBy { it.name.lowercase() }
    }

    fun publicTree(handle: String): List<PublicFolderDto> = dbBlocking {
        val user = userRowByHandleOrAlias(handle) ?: notFound()
        if (user[Users.banned]) notFound()
        publicTreeTx(user[Users.id].value)
    }

    fun list(userId: Long): List<FolderDto> = dbBlocking {
        val count = Posts.id.count()
        val counts = Posts.select(Posts.folder, count).where { (Posts.user eq userId) and Posts.folder.isNotNull() }
            .groupBy(Posts.folder).associate { it[Posts.folder]!!.value to it[count] }
        Folders.selectAll().where { Folders.user eq userId }.orderBy(Folders.name.lowerCase())
            .map { FolderDto(it[Folders.id].value, it[Folders.parent]?.value, it[Folders.name], counts[it[Folders.id].value] ?: 0) }
    }

    fun create(userId: Long, input: FolderInput): FolderDto = dbBlocking {
        val name = input.name?.trim()?.take(100)?.ifEmpty { null } ?: badRequest("name_required")
        input.parentId?.let { requireOwn(userId, it) }
        checkUnique(userId, input.parentId, name, null)
        if (Folders.selectAll().where { Folders.user eq userId }.count() >= 1000) badRequest("too_many")
        val id = Folders.insertAndGetId {
            it[user] = userId
            it[parent] = input.parentId
            it[Folders.name] = name
            it[createdAt] = Instant.now()
        }.value
        FolderDto(id, input.parentId, name)
    }

    fun update(userId: Long, id: Long, input: FolderInput): FolderDto = dbBlocking {
        val row = requireOwn(userId, id)
        val name = input.name?.trim()?.take(100)?.ifEmpty { null } ?: row[Folders.name]
        val parent = when {
            input.moveToRoot -> null
            input.parentId != null -> {
                requireOwn(userId, input.parentId)
                // Interdit de déplacer un dossier dans lui-même ou un de ses descendants.
                var cursor: Long? = input.parentId
                while (cursor != null) {
                    if (cursor == id) badRequest("folder_cycle")
                    cursor = Folders.select(Folders.parent).where { Folders.id eq cursor!! }.single()[Folders.parent]?.value
                }
                input.parentId
            }
            else -> row[Folders.parent]?.value
        }
        checkUnique(userId, parent, name, id)
        Folders.update({ Folders.id eq id }) { it[Folders.name] = name; it[Folders.parent] = parent }
        FolderDto(id, parent, name)
    }

    /** Supprime le dossier et ses sous-dossiers ; les posts qu'ils contiennent remontent à la racine. */
    fun delete(userId: Long, id: Long) = dbBlocking {
        requireOwn(userId, id)
        Folders.deleteWhere { Folders.id eq id }
    }

    private fun requireOwn(userId: Long, id: Long): ResultRow =
        Folders.selectAll().where { (Folders.id eq id) and (Folders.user eq userId) }.singleOrNull() ?: notFound()

    private fun checkUnique(userId: Long, parentId: Long?, name: String, exceptId: Long?) {
        var cond = (Folders.user eq userId) and (Folders.name.lowerCase() eq name.lowercase())
        cond = cond and (if (parentId == null) Folders.parent.isNull() else Folders.parent eq parentId)
        if (exceptId != null) cond = cond and (Folders.id neq exceptId)
        if (Folders.selectAll().where { cond }.any()) conflict("folder_exists")
    }
}

object TokenService {
    val SCOPES = setOf("posts:read", "posts:write")
    private const val PREFIX = "txt_"

    data class TokenPrincipal(val userId: Long, val scopes: Set<String>)

    fun list(userId: Long): List<TokenDto> = dbBlocking {
        ApiTokens.selectAll().where { ApiTokens.user eq userId }.orderBy(ApiTokens.createdAt, SortOrder.DESC).map(::toDto)
    }

    fun create(userId: Long, input: TokenInput): TokenCreatedDto = dbBlocking {
        if (ApiTokens.selectAll().where { ApiTokens.user eq userId }.count() >= 25) badRequest("too_many")
        val scopes = input.scopes.filter { it in SCOPES }.ifEmpty { badRequest("scopes_required") }
        val secret = PREFIX + Crypto.randomKey(40)
        val now = Instant.now()
        val id = ApiTokens.insertAndGetId {
            it[user] = userId
            it[name] = input.name.trim().take(100).ifEmpty { "Token" }
            it[tokenHash] = Crypto.sha256(secret)
            it[prefix] = secret.take(10)
            it[ApiTokens.scopes] = scopes.joinToString(",")
            it[createdAt] = now
            it[expiresAt] = input.expiresInDays?.takeIf { d -> d > 0 }?.let { d -> now.plusSeconds(d * 86400L) }
        }
        TokenCreatedDto(toDto(ApiTokens.selectAll().where { ApiTokens.id eq id }.single()), secret)
    }

    fun delete(userId: Long, id: Long) = dbBlocking {
        if (ApiTokens.deleteWhere { (ApiTokens.id eq id) and (ApiTokens.user eq userId) } == 0) notFound()
    }

    /** Valide un token (nouveau format `txt_...` ou ancienne clé API de la v1, stockée hachée à l'import). */
    fun authenticate(secret: String): TokenPrincipal? = dbBlocking {
        val row = ApiTokens.selectAll().where { ApiTokens.tokenHash eq Crypto.sha256(secret) }.singleOrNull()
            ?: return@dbBlocking null
        if (row[ApiTokens.expiresAt]?.isBefore(Instant.now()) == true) return@dbBlocking null
        val banned = Users.select(Users.banned).where { Users.id eq row[ApiTokens.user] }.single()[Users.banned]
        if (banned) return@dbBlocking null
        ApiTokens.update({ ApiTokens.id eq row[ApiTokens.id] }) { it[lastUsedAt] = Instant.now() }
        TokenPrincipal(row[ApiTokens.user].value, row[ApiTokens.scopes].split(',').toSet())
    }

    private fun toDto(it: ResultRow) = TokenDto(
        id = it[ApiTokens.id].value,
        name = it[ApiTokens.name],
        prefix = it[ApiTokens.prefix],
        scopes = it[ApiTokens.scopes].split(',').filter(String::isNotEmpty),
        legacy = it[ApiTokens.legacy],
        createdAt = it[ApiTokens.createdAt].toEpochMilli(),
        lastUsedAt = it[ApiTokens.lastUsedAt]?.toEpochMilli(),
        expiresAt = it[ApiTokens.expiresAt]?.toEpochMilli(),
    )
}

class AdminService(private val users: UserService) {
    fun stats(): AdminStatsDto = dbBlocking {
        val views = Posts.views.sum()
        val count = Posts.id.count()
        val size = Posts.content.charLength().sum()
        val unclaimed = Users.selectAll().where {
            Users.legacyId.isNotNull() and (Users.id notInSubQuery LinkedAccounts.select(LinkedAccounts.user))
        }.count()
        AdminStatsDto(
            users = Users.selectAll().count(),
            posts = Posts.selectAll().count(),
            views = Posts.select(views).single()[views]?.toLong() ?: 0,
            legacyUsersUnclaimed = unclaimed,
            postsByKind = Posts.select(Posts.kind, count).groupBy(Posts.kind).associate { it[Posts.kind] to it[count] },
            storageBytes = Posts.select(size).single()[size]?.toLong() ?: 0,
        )
    }

    fun users(q: String?, page: Int, pageSize: Int): PageDto<AdminUserDto> = dbBlocking {
        var cond: Op<Boolean> = Op.TRUE
        q?.trim()?.lowercase()?.takeIf(String::isNotEmpty)?.let { s ->
            cond = (Users.handle like "%$s%") or (Users.displayName.lowerCase() like "%$s%")
        }
        val total = Users.selectAll().where { cond }.count()
        val rows = Users.selectAll().where { cond }.orderBy(Users.createdAt, SortOrder.DESC)
            .limit(pageSize).offset(((page.coerceAtLeast(1) - 1) * pageSize).toLong()).toList()
        val ids = rows.map { it[Users.id].value }
        val accounts = LinkedAccounts.selectAll().where { LinkedAccounts.user inList ids }
            .groupBy({ it[LinkedAccounts.user].value }) { AdminAccountDto(it[LinkedAccounts.provider], it[LinkedAccounts.providerUserId], it[LinkedAccounts.username]) }
        val count = Posts.id.count()
        val posts = Posts.select(Posts.user, count).where { Posts.user inList ids }.groupBy(Posts.user)
            .associate { it[Posts.user].value to it[count] }
        PageDto(rows.map {
            val id = it[Users.id].value
            AdminUserDto(
                id, it[Users.handle], it[Users.displayName], it[Users.avatarUrl], it[Users.role], it[Users.banned],
                posts[id] ?: 0, accounts[id].orEmpty().map { a -> a.provider }, it[Users.legacyId] != null,
                it[Users.createdAt].toEpochMilli(), it[Users.lastLoginAt]?.toEpochMilli(), accounts[id].orEmpty(),
            )
        }, total, page, pageSize)
    }

    fun patchUser(adminId: Long, id: Long, patch: AdminUserPatch) = dbBlocking {
        if (id == adminId && (patch.banned == true || patch.role == "user")) badRequest("self")
        val n = Users.update({ Users.id eq id }) {
            patch.banned?.let { b -> it[banned] = b }
            patch.role?.takeIf { r -> r in setOf("user", "admin") }?.let { r -> it[role] = r }
        }
        if (n == 0) notFound()
    }

    /**
     * Lie à la main un ID Discord ou Twitch à un compte (typiquement un compte v1 pas encore reconnecté) : à sa prochaine
     * connexion avec cet ID, la personne arrive sur ce compte. [username]/[avatarUrl] viennent d'une recherche si possible.
     */
    fun linkAccount(adminId: Long, userId: Long, provider: String, providerUserId: String, username: String?, avatarUrl: String?) =
        users.linkManually(userId, provider, providerUserId, username, avatarUrl).also {
            org.slf4j.LoggerFactory.getLogger("Audit").info("Admin {} lie {}:{} au compte {}", adminId, provider, providerUserId, userId)
        }

    fun unlinkAccount(adminId: Long, userId: Long, provider: String) = dbBlocking {
        if (LinkedAccounts.deleteWhere { (LinkedAccounts.user eq userId) and (LinkedAccounts.provider eq provider) } == 0) notFound()
        org.slf4j.LoggerFactory.getLogger("Audit").info("Admin {} délie {} du compte {}", adminId, provider, userId)
    }

    /** Supprime définitivement un compte et tout son contenu (posts, dossiers, tags, jetons), sans passer par le bannissement. */
    fun deleteUser(adminId: Long, id: Long) = dbBlocking {
        if (id == adminId) badRequest("self")
        if (Users.deleteWhere { Users.id eq id } == 0) notFound()
    }

    /** Réassigne tout le contenu d'un compte (ex : compte v1 orphelin) à un autre, puis le supprime. */
    fun merge(sourceId: Long, targetId: Long) = dbBlocking {
        if (Users.selectAll().where { Users.id inList listOf(sourceId, targetId) }.count() != 2L) notFound()
        users.mergeUsers(sourceId, targetId)
    }
}
