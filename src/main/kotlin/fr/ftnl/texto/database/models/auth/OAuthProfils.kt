package fr.ftnl.texto.database.models.auth

import fr.ftnl.texto.database.models.User
import fr.ftnl.texto.database.models.Users
import fr.ftnl.texto.database.types.BaseIntEntity
import fr.ftnl.texto.database.types.BaseIntEntityClass
import fr.ftnl.texto.database.types.BaseIntIdTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq


object OAuthProfils : BaseIntIdTable("TBL_OAUTH_PROFILES_OAP") {
    
    val user = reference("user", Users, onDelete = ReferenceOption.CASCADE)
    val provider = reference("provider", OAuthProviders, onDelete = ReferenceOption.CASCADE)
    val profileId = varchar("profile_id", 255)
    
    init {
        uniqueIndex("unique_provider_profile_id", provider, profileId)
    }
}


class OAuthProfil(id: EntityID<Int>): BaseIntEntity(id, OAuthProfils) {
    
    companion object : BaseIntEntityClass<OAuthProfil>(OAuthProfils) {
        fun create(user: User, provider: OAuthProvider, profileId: String): OAuthProfil = new {
            _user = user
            _provider = provider
            _profileId = profileId
        }
        
        
        fun getByProfileIdAndProvider(profileId: String, provider: OAuthProvider) =
            find { (OAuthProfils.profileId eq profileId) and (OAuthProfils.provider eq provider.id) }.firstOrNull()
        
    }
    
    private var _user by User.Companion referencedOn OAuthProfils.user
    val user: User get() = _user
    
    private var _provider by OAuthProvider referencedOn OAuthProfils.provider
    val provider: OAuthProvider get() = _provider
    
    private var _profileId by OAuthProfils.profileId
    val profileId: String get() = _profileId


}