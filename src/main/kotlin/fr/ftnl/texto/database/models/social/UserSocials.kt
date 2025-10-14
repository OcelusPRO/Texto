package fr.ftnl.texto.database.models.social

import fr.ftnl.texto.database.models.User
import fr.ftnl.texto.database.models.Users
import fr.ftnl.texto.database.types.BaseIntEntity
import fr.ftnl.texto.database.types.BaseIntEntityClass
import fr.ftnl.texto.database.types.BaseIntIdTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID

object UserSocials : BaseIntIdTable("TBL_USERS_SOCIALS_USO") {

    val user = reference("user", Users)
    val socialProvider = reference("socialProvider", SocialProviders)
    val socialName = varchar("socialName", 255)

    init {
        uniqueIndex("unique_provider_social_name", socialName, socialProvider, user)
    }
}


class UserSocial(id: EntityID<Int>): BaseIntEntity(id, UserSocials) {
    
    companion object : BaseIntEntityClass<UserSocial>(UserSocials) {
        fun create(user: User, socialProvider: SocialProvider, socialName: String): UserSocial = new {
            _user = user
            this.socialProvider = socialProvider
            this.socialName = socialName
        }
    }
    
    private var _user by User.Companion referencedOn UserSocials.user
    val user: User get() = _user
    
    var socialProvider by SocialProvider referencedOn UserSocials.socialProvider
    var socialName by UserSocials.socialName
    
    val socialUrl: String
        get() = socialProvider.providerBaseUrl + socialName
    
}