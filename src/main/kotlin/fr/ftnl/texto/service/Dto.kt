package fr.ftnl.texto.service

import kotlinx.serialization.Serializable

@Serializable
data class SocialDto(val icon: String, val url: String)

@Serializable
data class UserMini(val handle: String, val displayName: String, val avatarUrl: String?)

@Serializable
data class UserPublicDto(
    val handle: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String,
    val socials: List<SocialDto>,
    val createdAt: Long,
    val publicPosts: Long,
    val totalViews: Long,
)

@Serializable
data class LinkedAccountDto(val provider: String, val username: String, val avatarUrl: String?, val linkedAt: Long)

@Serializable
data class MeDto(
    val id: Long,
    val handle: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String,
    val role: String,
    val locale: String?,
    val accounts: List<LinkedAccountDto>,
    val socials: List<SocialDto>,
    /** Renseigné quand un administrateur agit en tant que ce compte : l'administrateur réel. */
    val impersonatedBy: UserMini? = null,
)

@Serializable
data class MePatch(
    val handle: String? = null,
    val displayName: String? = null,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val locale: String? = null,
)

@Serializable
data class TagDto(val id: Long, val name: String, val color: String, val postCount: Long = 0)

@Serializable
data class TagInput(val name: String, val color: String? = null)

@Serializable
data class FolderDto(val id: Long, val parentId: Long?, val name: String, val postCount: Long = 0)

/** Dossier visible sur un profil public : seulement ceux qui contiennent au moins un post public (directement ou dedans). */
@Serializable
data class PublicFolderDto(val id: Long, val parentId: Long?, val name: String, val postCount: Long, val directCount: Long)

@Serializable
data class FolderInput(val name: String? = null, val parentId: Long? = null, val moveToRoot: Boolean = false)

@Serializable
data class PostSummaryDto(
    val key: String,
    val title: String,
    val description: String,
    val kind: String,
    val language: String?,
    val visibility: String,
    val views: Int,
    val revision: Int,
    val tags: List<TagDto>,
    val folderId: Long?,
    val hasPassword: Boolean,
    val burnAfterViews: Int?,
    val hasThumbnail: Boolean,
    val size: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long?,
    val author: UserMini? = null,
)

@Serializable
data class PostDto(
    val summary: PostSummaryDto,
    val content: String,
    val author: UserMini,
    val isOwner: Boolean,
    /** Vrai si cette lecture a détruit le post (brûler après lecture). */
    val burned: Boolean = false,
)

@Serializable
data class PostInput(
    val title: String? = null,
    val description: String? = null,
    val kind: String? = null,
    val language: String? = null,
    val content: String? = null,
    val visibility: String? = null,
    /** null : inchangé, "" : retire le mot de passe. */
    val password: String? = null,
    /** 0 : désactive. */
    val burnAfterViews: Int? = null,
    /** Durée de vie en secondes à partir de maintenant ; 0 : jamais. */
    val expiresIn: Long? = null,
    val folderId: Long? = null,
    val clearFolder: Boolean = false,
    val tags: List<String>? = null,
)

@Serializable
data class RevisionSummaryDto(val number: Int, val title: String, val size: Int, val createdAt: Long)

@Serializable
data class RevisionDto(
    val number: Int,
    val title: String,
    val description: String,
    val kind: String,
    val language: String?,
    val content: String,
    val createdAt: Long,
)

@Serializable
data class PageDto<T>(val items: List<T>, val total: Long, val page: Int, val pageSize: Int)

@Serializable
data class TokenDto(
    val id: Long,
    val name: String,
    val prefix: String,
    val scopes: List<String>,
    val legacy: Boolean,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val expiresAt: Long?,
)

@Serializable
data class TokenInput(val name: String, val scopes: List<String> = listOf("posts:read", "posts:write"), val expiresInDays: Int? = null)

@Serializable
data class TokenCreatedDto(val token: TokenDto, val secret: String)

@Serializable
data class PublicConfigDto(
    val siteName: String,
    val baseUrl: String,
    val providers: List<String>,
    val maxContentLength: Int,
    val factorioAssets: Boolean,
    val allowNewUsers: Boolean,
    /** URL de Factorio Blueprint Editor auto-hébergé (iframe), null si non configuré. */
    val fbeUrl: String? = null,
    /** Icônes de liens sociaux disponibles, avec les règles du lien associé. */
    val socials: List<SocialDefDto> = emptyList(),
)

@Serializable
data class SocialRuleDto(val host: String, val pathPrefix: String? = null)

@Serializable
data class SocialDefDto(val key: String, val label: String, val placeholder: String, val rules: List<SocialRuleDto>, val embed: Boolean, val allowHttp: Boolean)

@Serializable
data class AdminSettingsDto(val allowNewUsers: Boolean)

@Serializable
data class AdminStatsDto(
    val users: Long,
    val posts: Long,
    val views: Long,
    val legacyUsersUnclaimed: Long,
    val postsByKind: Map<String, Long>,
    val storageBytes: Long,
)

@Serializable
data class AdminUserDto(
    val id: Long,
    val handle: String,
    val displayName: String,
    val avatarUrl: String?,
    val role: String,
    val banned: Boolean,
    val posts: Long,
    val providers: List<String>,
    val legacy: Boolean,
    val createdAt: Long,
    val lastLoginAt: Long?,
    /** Comptes Discord/Twitch liés (avec leurs ID), pour la liaison manuelle. */
    val accounts: List<AdminAccountDto> = emptyList(),
)

@Serializable
data class AdminAccountDto(val provider: String, val providerUserId: String, val username: String)

@Serializable
data class AdminLinkInput(val provider: String, val providerUserId: String)

@Serializable
data class AdminUserPatch(val banned: Boolean? = null, val role: String? = null)

@Serializable
data class ErrorDto(val error: String, val message: String? = null)

/** Ancien format d'envoi de la v1 (POST /new-texto/api). */
@Serializable
data class LegacyPostData(
    val content: String,
    val title: String,
    val description: String = "",
    val public: Boolean = false,
    val expire: Long? = null,
)
