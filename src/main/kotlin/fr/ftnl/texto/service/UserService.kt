package fr.ftnl.texto.service

import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.db.*
import fr.ftnl.texto.util.Crypto
import fr.ftnl.texto.util.SocialIcons
import fr.ftnl.texto.util.SocialRules
import fr.ftnl.texto.util.TokenCipher
import fr.ftnl.texto.util.badRequest
import fr.ftnl.texto.util.conflict
import fr.ftnl.texto.util.forbidden
import fr.ftnl.texto.util.notFound
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.text.Normalizer
import java.time.Instant

/** Profil renvoyé par un fournisseur OAuth (Discord, Twitch). */
data class OAuthProfile(
    val provider: String,
    val providerUserId: String,
    val username: String,
    val avatarUrl: String?,
    val tokens: StoredTokens? = null,
)

/** Jetons OAuth d'un compte lié (en clair en mémoire, chiffrés en base). */
data class StoredTokens(val accessToken: String?, val expiresAt: Instant?, val refreshToken: String?)

sealed interface LoginResult {
    data class Ok(val userId: Long, val merged: Boolean = false, val claimedLegacy: Boolean = false) : LoginResult
    data object Banned : LoginResult
    data object RegistrationClosed : LoginResult
}

/** Utilisateur par identifiant actuel, ou par ancien identifiant (alias). À appeler dans une transaction. */
fun userRowByHandleOrAlias(handle: String): ResultRow? {
    val h = handle.lowercase()
    Users.selectAll().where { Users.handle eq h }.singleOrNull()?.let { return it }
    val uid = HandleAliases.selectAll().where { HandleAliases.handle eq h }.singleOrNull()?.get(HandleAliases.user) ?: return null
    return Users.selectAll().where { Users.id eq uid }.singleOrNull()
}

class UserService(private val cfg: AppConfig) {
    private val cipher = TokenCipher(cfg.sessionEncryptKey + "|" + cfg.sessionSignKey)

    // ---------------------------------------------------------------- réglages modifiables par l'administration

    /** Inscriptions ouvertes : réglage de l'administration, sinon ALLOW_NEW_USER. À appeler dans une transaction. */
    private fun registrationOpenTx(): Boolean =
        AppMeta.selectAll().where { AppMeta.key eq "allow_new_users" }.singleOrNull()?.get(AppMeta.value)?.toBooleanStrictOrNull() ?: cfg.allowNewUsers

    fun registrationOpen(): Boolean = dbBlocking { registrationOpenTx() }

    fun setRegistrationOpen(open: Boolean) = dbBlocking {
        AppMeta.deleteWhere { AppMeta.key eq "allow_new_users" }
        AppMeta.insert { it[key] = "allow_new_users"; it[value] = open.toString() }
    }

    // ---------------------------------------------------------------- jetons OAuth

    private fun tokenValues(tokens: StoredTokens?) = tokens?.let {
        Triple(it.accessToken?.let(cipher::encrypt), it.refreshToken?.let(cipher::encrypt), it.expiresAt)
    }

    /** Jetons lisibles d'un compte lié (null s'il n'y en a pas ou s'ils sont illisibles). */
    fun storedTokens(account: ResultRow): StoredTokens? {
        val access = account[LinkedAccounts.accessToken]?.let(cipher::decrypt)
        val refresh = account[LinkedAccounts.refreshToken]?.let(cipher::decrypt)
        if (access == null && refresh == null) return null
        return StoredTokens(access, account[LinkedAccounts.tokenExpiresAt], refresh)
    }

    fun saveTokens(accountId: Long, tokens: StoredTokens) = dbBlocking {
        val v = tokenValues(tokens)!!
        LinkedAccounts.update({ LinkedAccounts.id eq accountId }) {
            it[accessToken] = v.first
            it[refreshToken] = v.second
            it[tokenExpiresAt] = v.third
        }
    }

    // ---------------------------------------------------------------- connexion / liaison

    /**
     * Connecte (ou lie, si [currentUserId] est fourni) un compte OAuth.
     *
     * - compte OAuth déjà connu : connexion à son utilisateur. Si on est déjà connecté sous un autre
     *   utilisateur, les deux comptes sont fusionnés dans l'utilisateur courant (liaison Discord + Twitch).
     * - inconnu mais l'identité (ID Discord / image Twitch) correspond à un compte de la v1 : rattachement au compte importé.
     * - sinon : création d'un utilisateur (ou ajout au compte courant en cas de liaison).
     */
    fun loginOrLink(profile: OAuthProfile, currentUserId: Long?): LoginResult = dbBlocking {
        val now = Instant.now()
        val existing = LinkedAccounts.selectAll()
            .where { (LinkedAccounts.provider eq profile.provider) and (LinkedAccounts.providerUserId eq profile.providerUserId) }
            .singleOrNull()

        var merged = false
        var claimed = false
        val userId: Long = when {
            existing != null -> {
                val owner = existing[LinkedAccounts.user].value
                LinkedAccounts.update({ LinkedAccounts.id eq existing[LinkedAccounts.id] }) {
                    it[username] = profile.username
                    it[avatarUrl] = profile.avatarUrl
                    tokenValues(profile.tokens)?.let { v ->
                        it[accessToken] = v.first
                        it[refreshToken] = v.second ?: existing[LinkedAccounts.refreshToken]
                        it[tokenExpiresAt] = v.third
                    }
                }
                if (currentUserId != null && currentUserId != owner) {
                    mergeUsers(sourceId = owner, targetId = currentUserId)
                    merged = true
                    currentUserId
                } else owner
            }

            currentUserId != null -> {
                val hasSameProvider = LinkedAccounts.selectAll()
                    .where { (LinkedAccounts.user eq currentUserId) and (LinkedAccounts.provider eq profile.provider) }
                    .any()
                if (hasSameProvider) conflict("provider_already_linked")
                insertLinked(currentUserId, profile, now)
                currentUserId
            }

            else -> {
                val matches = findUnclaimedLegacy(profile)
                val legacy = matches.firstOrNull()
                if (legacy != null) {
                    matches.drop(1).forEach { mergeUsers(it, legacy) }
                    claimed = true
                    insertLinked(legacy, profile, now)
                    if (profile.avatarUrl != null) Users.update({ Users.id eq legacy }) { it[avatarUrl] = profile.avatarUrl }
                    legacy
                } else {
                    if (!registrationOpenTx()) return@dbBlocking LoginResult.RegistrationClosed
                    val id = Users.insertAndGetId {
                        it[handle] = uniqueHandle(profile.username)
                        it[displayName] = profile.username.take(100)
                        it[avatarUrl] = profile.avatarUrl
                        it[createdAt] = now
                    }.value
                    insertLinked(id, profile, now)
                    id
                }
            }
        }

        // Un compte v1 non réclamé peut correspondre à ce profil (même identité Discord/Twitch) : on le fusionne,
        // y compris à une connexion ultérieure.
        if (existing != null || currentUserId != null) {
            val absorbed = findUnclaimedLegacy(profile).filter { it != userId }
            absorbed.forEach { mergeUsers(it, userId) }
            if (absorbed.isNotEmpty()) claimed = true
        }

        val user = Users.selectAll().where { Users.id eq userId }.single()
        if (user[Users.banned]) return@dbBlocking LoginResult.Banned
        val isAdmin = "${profile.provider}:${profile.providerUserId}".lowercase() in cfg.admins
        Users.update({ Users.id eq userId }) {
            it[lastLoginAt] = now
            if (isAdmin) it[role] = "admin"
        }
        LoginResult.Ok(userId, merged, claimed)
    }

    /**
     * Comptes importés de la v1, sans aucun compte OAuth lié, qui correspondent à ce profil par leur identité :
     * l'ID Discord contenu dans l'URL d'avatar enregistrée par la v1, ou l'identifiant unique de l'image de profil Twitch.
     * Aucun email n'est utilisé ni conservé : un compte est relié à ses connexions par leurs ID Discord / Twitch.
     */
    private fun findUnclaimedLegacy(profile: OAuthProfile): List<Long> {
        val needle = avatarIdentity(profile) ?: return emptyList()
        return Users.selectAll().where { Users.legacyId.isNotNull() }
            .filter { u ->
                u[Users.avatarUrl]?.contains(needle) == true &&
                    LinkedAccounts.selectAll().where { LinkedAccounts.user eq u[Users.id].value }.empty()
            }
            .map { it[Users.id].value }
    }

    private fun avatarIdentity(p: OAuthProfile): String? = when (p.provider) {
        "discord" -> "cdn.discordapp.com/avatars/${p.providerUserId}/"
        "twitch" -> p.avatarUrl?.let { TWITCH_IMAGE.find(it)?.groupValues?.get(1) }?.let { "jtv_user_pictures/$it-profile_image" }
        else -> null
    }

    private fun insertLinked(userId: Long, p: OAuthProfile, now: Instant) {
        LinkedAccounts.insert {
            it[user] = userId
            it[provider] = p.provider
            it[providerUserId] = p.providerUserId
            it[username] = p.username.take(100)
            it[avatarUrl] = p.avatarUrl
            it[linkedAt] = now
            tokenValues(p.tokens)?.let { v ->
                it[accessToken] = v.first
                it[refreshToken] = v.second
                it[tokenExpiresAt] = v.third
            }
        }
    }

    /** ID Discord (flocon numérique, 17 à 20 chiffres) ou Twitch (numérique) : jamais un pseudo. */
    fun validateProviderId(provider: String, providerUserId: String) {
        if (provider !in setOf("discord", "twitch")) badRequest("invalid_provider")
        val ok = if (provider == "discord") Regex("""\d{17,20}""").matches(providerUserId) else Regex("""\d{1,15}""").matches(providerUserId)
        if (!ok) badRequest("invalid_provider_id")
    }

    /** Liaison manuelle d'un ID de connexion à un compte existant (administration). Sans jeton : il viendra à la première connexion. */
    fun linkManually(userId: Long, provider: String, providerUserId: String, username: String?, avatarUrl: String?) = dbBlocking {
        validateProviderId(provider, providerUserId)
        if (Users.selectAll().where { Users.id eq userId }.empty()) notFound()
        val owner = LinkedAccounts.selectAll()
            .where { (LinkedAccounts.provider eq provider) and (LinkedAccounts.providerUserId eq providerUserId) }.singleOrNull()
        if (owner != null) {
            val ownerId = owner[LinkedAccounts.user].value
            if (ownerId == userId) conflict("already_linked_here")
            val handle = Users.select(Users.handle).where { Users.id eq ownerId }.single()[Users.handle]
            conflict("already_linked", handle)
        }
        if (LinkedAccounts.selectAll().where { (LinkedAccounts.user eq userId) and (LinkedAccounts.provider eq provider) }.any()) {
            conflict("provider_already_linked")
        }
        insertLinked(userId, OAuthProfile(provider, providerUserId, (username ?: providerUserId), avatarUrl), Instant.now())
    }

    fun unlink(userId: Long, provider: String) = dbBlocking {
        val count = LinkedAccounts.selectAll().where { LinkedAccounts.user eq userId }.count()
        if (count <= 1) badRequest("last_account", "Impossible de retirer le dernier compte lié")
        val deleted = LinkedAccounts.deleteWhere { (LinkedAccounts.user eq userId) and (LinkedAccounts.provider eq provider) }
        if (deleted == 0) notFound()
    }

    /**
     * Fusionne [sourceId] dans [targetId] : posts, dossiers, tags, tokens, comptes liés, liens sociaux.
     * L'utilisateur source est supprimé.
     */
    fun mergeUsers(sourceId: Long, targetId: Long) {
        if (sourceId == targetId) return
        // Tags : même nom (insensible à la casse) => on garde celui de la cible.
        val targetTags = Tags.selectAll().where { Tags.user eq targetId }.associate { it[Tags.name].lowercase() to it[Tags.id].value }
        Tags.selectAll().where { Tags.user eq sourceId }.toList().forEach { tag ->
            val srcTagId = tag[Tags.id].value
            val dst = targetTags[tag[Tags.name].lowercase()]
            if (dst != null) {
                val postIds = PostTags.selectAll().where { PostTags.tag eq srcTagId }.map { it[PostTags.post].value }
                postIds.forEach { pid ->
                    PostTags.insertIgnore { it[post] = pid; it[PostTags.tag] = dst }
                }
                Tags.deleteWhere { Tags.id eq srcTagId }
            } else {
                Tags.update({ Tags.id eq srcTagId }) { it[user] = targetId }
            }
        }
        // Dossiers racine : on renomme en cas de collision.
        val rootNames = Folders.selectAll().where { (Folders.user eq targetId) and Folders.parent.isNull() }
            .map { it[Folders.name].lowercase() }.toMutableSet()
        Folders.selectAll().where { (Folders.user eq sourceId) and Folders.parent.isNull() }.toList().forEach { f ->
            var name = f[Folders.name]
            var i = 2
            while (name.lowercase() in rootNames) name = "${f[Folders.name]} ($i)".also { i++ }
            rootNames += name.lowercase()
            Folders.update({ Folders.id eq f[Folders.id] }) { it[Folders.name] = name }
        }
        Folders.update({ Folders.user eq sourceId }) { it[user] = targetId }
        Posts.update({ Posts.user eq sourceId }) { it[user] = targetId }
        ApiTokens.update({ ApiTokens.user eq sourceId }) { it[user] = targetId }
        SocialLinks.update({ SocialLinks.user eq sourceId }) { it[user] = targetId }
        val srcHandle = Users.selectAll().where { Users.id eq sourceId }.single()[Users.handle]
        HandleAliases.update({ HandleAliases.user eq sourceId }) { it[user] = targetId }
        HandleAliases.insertIgnore { it[handle] = srcHandle; it[user] = targetId; it[createdAt] = Instant.now() }
        val targetProviders = LinkedAccounts.selectAll().where { LinkedAccounts.user eq targetId }.map { it[LinkedAccounts.provider] }
        LinkedAccounts.deleteWhere { (LinkedAccounts.user eq sourceId) and (LinkedAccounts.provider inList targetProviders) }
        LinkedAccounts.update({ LinkedAccounts.user eq sourceId }) { it[user] = targetId }

        val src = Users.selectAll().where { Users.id eq sourceId }.single()
        val dst = Users.selectAll().where { Users.id eq targetId }.single()
        val legacyId = dst[Users.legacyId] ?: src[Users.legacyId]
        val bio = dst[Users.bio].ifBlank { src[Users.bio] }
        val avatar = dst[Users.avatarUrl] ?: src[Users.avatarUrl]
        val role = if (src[Users.role] == "admin") "admin" else dst[Users.role]
        Users.deleteWhere { Users.id eq sourceId }
        Users.update({ Users.id eq targetId }) {
            it[Users.legacyId] = legacyId
            it[Users.bio] = bio
            it[avatarUrl] = avatar
            it[Users.role] = role
        }
    }

    // ---------------------------------------------------------------- profils

    fun me(userId: Long): MeDto = dbBlocking {
        val u = Users.selectAll().where { Users.id eq userId }.singleOrNull() ?: notFound()
        MeDto(
            id = userId,
            handle = u[Users.handle],
            displayName = u[Users.displayName],
            avatarUrl = u[Users.avatarUrl],
            bio = u[Users.bio],
            role = u[Users.role],
            locale = u[Users.locale],
            accounts = LinkedAccounts.selectAll().where { LinkedAccounts.user eq userId }.map {
                LinkedAccountDto(it[LinkedAccounts.provider], it[LinkedAccounts.username], it[LinkedAccounts.avatarUrl], it[LinkedAccounts.linkedAt].toEpochMilli())
            },
            socials = socials(userId),
        )
    }

    fun mini(userId: Long): UserMini = dbBlocking {
        val u = Users.selectAll().where { Users.id eq userId }.singleOrNull() ?: notFound()
        UserMini(u[Users.handle], u[Users.displayName], u[Users.avatarUrl])
    }

    /** Un compte peut-il être endossé par un administrateur ? (existant, actif, et pas le sien) */
    fun checkImpersonationTarget(adminId: Long, targetId: Long) = dbBlocking {
        if (adminId == targetId) badRequest("self")
        val u = Users.selectAll().where { Users.id eq targetId }.singleOrNull() ?: notFound()
        if (u[Users.banned]) badRequest("banned")
    }

    fun isActive(userId: Long): Boolean = dbBlocking {
        Users.selectAll().where { (Users.id eq userId) and (Users.banned eq false) }.any()
    }

    fun role(userId: Long): String? = dbBlocking {
        Users.select(Users.role).where { Users.id eq userId }.singleOrNull()?.get(Users.role)
    }

    fun updateMe(userId: Long, patch: MePatch): MeDto {
        dbBlocking {
            val newHandle = patch.handle?.trim()?.lowercase()?.takeIf { it != Users.selectAll().where { Users.id eq userId }.single()[Users.handle] }
            if (newHandle != null) {
                if (!HANDLE_REGEX.matches(newHandle) || newHandle in RESERVED) badRequest("invalid_handle")
                val taken = Users.selectAll().where { (Users.handle eq newHandle) and (Users.id neq userId) }.any()
                val aliasOfOther = HandleAliases.selectAll().where { (HandleAliases.handle eq newHandle) and (HandleAliases.user neq userId) }.any()
                if (taken || aliasOfOther) conflict("handle_taken")
                changeHandle(userId, newHandle)
            }
            Users.update({ Users.id eq userId }) {
                it[profileEdited] = true
                patch.displayName?.let { d -> it[displayName] = d.trim().take(100).ifBlank { "?" } }
                patch.bio?.let { b -> it[bio] = b.take(2000) }
                patch.avatarUrl?.let { a -> it[avatarUrl] = a.takeIf { url -> url.startsWith("https://") }?.take(512) }
                patch.locale?.let { l -> it[locale] = l.takeIf { v -> v in setOf("fr", "en") } }
            }
        }
        return me(userId)
    }

    /** Change l'identifiant d'URL : l'ancien reste actif comme alias et redirige vers le nouveau. */
    fun changeHandle(userId: Long, newHandle: String) {
        val old = Users.selectAll().where { Users.id eq userId }.single()[Users.handle]
        if (old == newHandle) return
        HandleAliases.deleteWhere { (HandleAliases.handle eq newHandle) and (HandleAliases.user eq userId) }
        HandleAliases.insertIgnore { it[handle] = old; it[user] = userId; it[createdAt] = Instant.now() }
        Users.update({ Users.id eq userId }) { it[handle] = newHandle }
    }

    /** Identifiant actuel d'un utilisateur, à partir de son identifiant ou d'un ancien identifiant ; null si inconnu ou banni. */
    fun canonicalHandle(handle: String): String? = dbBlocking {
        userRowByHandleOrAlias(handle)?.takeIf { !it[Users.banned] }?.get(Users.handle)
    }

    /** Identifiant actuel pour un ancien identifiant (alias), ou null. */
    fun currentHandleForAlias(handle: String): String? = dbBlocking {
        val uid = HandleAliases.selectAll().where { HandleAliases.handle eq handle.lowercase() }.singleOrNull()?.get(HandleAliases.user) ?: return@dbBlocking null
        Users.select(Users.handle).where { Users.id eq uid }.singleOrNull()?.get(Users.handle)
    }

    fun deleteMe(userId: Long) = dbBlocking { Users.deleteWhere { Users.id eq userId } }

    fun socials(userId: Long): List<SocialDto> =
        SocialLinks.selectAll().where { SocialLinks.user eq userId }.orderBy(SocialLinks.position)
            .map { SocialDto(it[SocialLinks.icon], it[SocialLinks.url]) }

    fun setSocials(userId: Long, list: List<SocialDto>) = dbBlocking {
        if (list.size > 12) badRequest("too_many")
        Users.update({ Users.id eq userId }) { it[profileEdited] = true }
        SocialLinks.deleteWhere { SocialLinks.user eq userId }
        list.forEachIndexed { i, s ->
            val key = SocialIcons.resolve(s.icon, s.url)
            if (!SocialRules.valid(key, s.url)) badRequest("invalid_social_url", key)
            SocialLinks.insert {
                it[user] = userId
                it[icon] = key
                it[url] = s.url.trim().take(512)
                it[position] = i
            }
        }
    }

    fun publicProfile(handle: String): UserPublicDto = dbBlocking {
        val u = userRowByHandleOrAlias(handle) ?: notFound()
        if (u[Users.banned]) notFound()
        val id = u[Users.id].value
        val publicFilter = (Posts.user eq id) and (Posts.visibility eq "public")
        val views = Posts.views.sum()
        UserPublicDto(
            handle = u[Users.handle],
            displayName = u[Users.displayName],
            avatarUrl = u[Users.avatarUrl],
            bio = u[Users.bio],
            socials = socials(id),
            createdAt = u[Users.createdAt].toEpochMilli(),
            publicPosts = Posts.selectAll().where { publicFilter }.count(),
            totalViews = Posts.select(views).where { Posts.user eq id }.single()[views]?.toLong() ?: 0,
        )
    }

    fun handleForLegacyId(legacyId: Int): String? = dbBlocking {
        Users.select(Users.handle).where { Users.legacyId eq legacyId }.singleOrNull()?.get(Users.handle)
    }

    fun requireAdmin(userId: Long) {
        if (role(userId) != "admin") forbidden()
    }

    fun uniqueHandle(base: String): String {
        val slug = slugify(base).ifBlank { "user" }.take(32)
        var candidate = if (slug in RESERVED) "$slug-1" else slug
        var i = 1
        while (Users.selectAll().where { Users.handle eq candidate }.any() || HandleAliases.selectAll().where { HandleAliases.handle eq candidate }.any()) {
            i++
            candidate = "$slug-$i"
        }
        return candidate
    }

    companion object {
        private val TWITCH_IMAGE = Regex("jtv_user_pictures/([0-9a-f-]{36})-profile_image")
        val HANDLE_REGEX = Regex("^[a-z0-9][a-z0-9_-]{1,38}$")
        val RESERVED = setOf("admin", "api", "login", "logout", "new", "settings", "dashboard", "u", "raw", "og", "assets")

        fun slugify(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
                .lowercase()
                .replace(Regex("[^a-z0-9_-]+"), "-")
                .trim('-')
    }
}
