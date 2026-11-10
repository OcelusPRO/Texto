package fr.ftnl.texto.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

object Users : LongIdTable("t_users") {
    val handle = varchar("handle", 40)
    val displayName = varchar("display_name", 100)
    val avatarUrl = varchar("avatar_url", 512).nullable()
    val role = varchar("role", 16).default("user")
    val banned = bool("banned").default(false)
    val bio = text("bio").default("")
    val locale = varchar("locale", 8).nullable()
    val legacyId = integer("legacy_id").nullable()
    val createdAt = timestamp("created_at")
    val lastLoginAt = timestamp("last_login_at").nullable()
    val profileEdited = bool("profile_edited").default(false)
    val lastSyncedAt = timestamp("last_synced_at").nullable()
}

object HandleAliases : Table("t_handle_aliases") {
    val handle = varchar("handle", 40)
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(handle)
}

object LinkedAccounts : LongIdTable("t_linked_accounts") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 16)
    val providerUserId = varchar("provider_user_id", 64)
    val username = varchar("username", 100)
    val avatarUrl = varchar("avatar_url", 512).nullable()
    val linkedAt = timestamp("linked_at")
    val accessToken = text("access_token").nullable()
    val refreshToken = text("refresh_token").nullable()
    val tokenExpiresAt = timestamp("token_expires_at").nullable()
}

object SocialLinks : LongIdTable("t_social_links") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val icon = varchar("icon", 50)
    val url = varchar("url", 512)
    val position = integer("position").default(0)
}

object ApiTokens : LongIdTable("t_api_tokens") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 100)
    val tokenHash = varchar("token_hash", 64)
    val prefix = varchar("prefix", 16)
    val scopes = varchar("scopes", 255)
    val legacy = bool("legacy").default(false)
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
}

/** Passkeys (WebAuthn) : connexion sans mot de passe, configurée depuis les paramètres du compte. */
object Passkeys : LongIdTable("t_passkeys") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val credentialId = binary("credential_id")
    /** Identifiant WebAuthn de l'utilisateur, enregistré dans l'authentificateur (aléatoire, partagé par les passkeys d'un compte). */
    val userHandle = binary("user_handle")
    val publicKeyCose = binary("public_key_cose")
    val signCount = long("sign_count").default(0)
    val name = varchar("name", 100)
    val transports = varchar("transports", 255).default("")
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
}

object Folders : LongIdTable("t_folders") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val parent = optReference("parent_id", Folders, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 100)
    val createdAt = timestamp("created_at")
}

object Tags : LongIdTable("t_tags") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 200)
    val color = varchar("color", 16).default("slate")
}

object Posts : LongIdTable("t_posts") {
    val key = varchar("key", 32)
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val folder = optReference("folder_id", Folders, onDelete = ReferenceOption.SET_NULL)
    val title = varchar("title", 200)
    val description = text("description").default("")
    val kind = varchar("kind", 16).default("code")
    val language = varchar("language", 40).nullable()
    val content = text("content")
    val visibility = varchar("visibility", 16).default("public")
    val passwordHash = varchar("password_hash", 255).nullable()
    val burnAfterViews = integer("burn_after_views").nullable()
    val views = integer("views").default(0)
    val revision = integer("revision").default(1)
    val hasThumbnail = bool("has_thumbnail").default(false)
    val legacy = bool("legacy").default(false)
    val expiresAt = timestamp("expires_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object PostRevisions : LongIdTable("t_post_revisions") {
    val post = reference("post_id", Posts, onDelete = ReferenceOption.CASCADE)
    val number = integer("number")
    val title = varchar("title", 200)
    val description = text("description")
    val kind = varchar("kind", 16)
    val language = varchar("language", 40).nullable()
    val content = text("content")
    val createdAt = timestamp("created_at")
}

object PostTags : Table("t_post_tags") {
    val post = reference("post_id", Posts, onDelete = ReferenceOption.CASCADE)
    val tag = reference("tag_id", Tags, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(post, tag)
}

object PostThumbnails : Table("t_post_thumbnails") {
    val post = reference("post_id", Posts, onDelete = ReferenceOption.CASCADE)
    val png = binary("png")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(post)
}

object AppMeta : Table("t_app_meta") {
    val key = varchar("key", 64)
    val value = text("value")
    override val primaryKey = PrimaryKey(key)
}
