package fr.ftnl.texto.service

import fr.ftnl.texto.auth.ProviderDirectory
import fr.ftnl.texto.auth.ProviderProfile
import fr.ftnl.texto.db.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Met à jour pseudo et avatar d'un utilisateur depuis ses comptes liés, au plus une fois par jour,
 * et seulement quand quelqu'un ouvre un de ses posts ou son profil (jamais en tâche de fond).
 *
 * Un utilisateur qui a enregistré ses paramètres (`profile_edited`) n'est plus modifié automatiquement :
 * seules les infos du compte lié (pseudo/avatar Discord ou Twitch) sont tenues à jour.
 * Sinon, si le pseudo change, l'identifiant d'URL suit : l'ancien reste actif et redirige vers le nouveau.
 */
class ProfileSync(
    private val users: UserService,
    private val directory: ProviderDirectory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val interval: Duration = Duration.ofDays(1),
) {
    private val log = LoggerFactory.getLogger(ProfileSync::class.java)

    /** Profil public d'un ID, sans jeton utilisateur (Twitch uniquement) ; null si introuvable ou indisponible. */
    suspend fun lookup(provider: String, providerUserId: String): ProviderProfile? = directory.fetch(provider, providerUserId, null)

    /** Lance la mise à jour en arrière-plan : ne ralentit jamais la requête. */
    fun touchAuthorOfPost(key: String) {
        scope.launch {
            val id = db { Posts.select(Posts.user).where { Posts.key eq key }.singleOrNull()?.get(Posts.user)?.value } ?: return@launch
            syncIfDue(id)
        }
    }

    fun touchHandle(handle: String) {
        scope.launch {
            val id = db { userRowByHandleOrAlias(handle)?.get(Users.id)?.value } ?: return@launch
            syncIfDue(id)
        }
    }

    /** Retourne vrai si une mise à jour a été tentée (au plus une par [interval] et par utilisateur). */
    suspend fun syncIfDue(userId: Long, now: Instant = Instant.now()): Boolean {
        // Réservation atomique : deux visiteurs simultanés ne déclenchent qu'une seule mise à jour.
        val claimed = db {
            Users.update({
                (Users.id eq userId) and (Users.banned eq false) and
                    (Users.lastSyncedAt.isNull() or (Users.lastSyncedAt less now.minus(interval)))
            }) { it[lastSyncedAt] = now } > 0
        }
        if (!claimed) return false
        val accounts = db { LinkedAccounts.selectAll().where { LinkedAccounts.user eq userId }.toList() }
        for (a in accounts) {
            val profile = directory.fetch(a[LinkedAccounts.provider], a[LinkedAccounts.providerUserId], users.storedTokens(a)) ?: continue
            // Les jetons renouvelés pendant l'appel (rotation du jeton de rafraîchissement) sont conservés.
            profile.tokens?.let { users.saveTokens(a[LinkedAccounts.id].value, it) }
            try {
                db { apply(userId, a, profile) }
            } catch (e: Exception) {
                log.warn("Mise à jour du profil $userId impossible", e)
            }
        }
        return true
    }

    private fun apply(userId: Long, account: ResultRow, p: ProviderProfile) {
        val oldName = account[LinkedAccounts.username]
        val oldAvatar = account[LinkedAccounts.avatarUrl]
        if (oldName == p.username && oldAvatar == p.avatarUrl) return
        LinkedAccounts.update({ LinkedAccounts.id eq account[LinkedAccounts.id] }) {
            it[username] = p.username.take(100)
            it[avatarUrl] = p.avatarUrl
        }
        val user = Users.selectAll().where { Users.id eq userId }.single()
        if (user[Users.profileEdited]) return
        if (p.avatarUrl != oldAvatar && user[Users.avatarUrl] == oldAvatar) {
            Users.update({ Users.id eq userId }) { it[avatarUrl] = p.avatarUrl }
        }
        if (p.username != oldName && user[Users.displayName] == oldName.take(100)) {
            Users.update({ Users.id eq userId }) { it[displayName] = p.username.take(100) }
            val current = user[Users.handle]
            val base = UserService.slugify(p.username).ifBlank { "user" }.take(32)
            val alreadyFits = current == base || Regex("^" + Regex.escape(base) + "-\\d+$").matches(current)
            if (!alreadyFits) users.changeHandle(userId, users.uniqueHandle(p.username))
        }
    }
}
