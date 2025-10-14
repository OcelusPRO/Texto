package fr.ftnl.texto.database.models.social

import fr.ftnl.texto.database.types.BaseIntEntity
import fr.ftnl.texto.database.types.BaseIntEntityClass
import fr.ftnl.texto.database.types.BaseIntIdTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID

object SocialProviders : BaseIntIdTable("TBL_SOCIAL_PROVIDERS_SPR") {
    
    val providerName = varchar("providerName", 255).uniqueIndex()
    val providerBaseUrl = text("providerBaseUrl")
    val providerIcon = text("providerIcon")
    
}


class SocialProvider(id: EntityID<Int>): BaseIntEntity(id, SocialProviders) {
    
    companion object : BaseIntEntityClass<SocialProvider>(SocialProviders) {
        
        fun create(name: String, baseUrl: String, icon: String) = new {
            providerName = name
            providerBaseUrl = baseUrl
            providerIcon = icon
        }
        
    }
    
    
    var providerName by SocialProviders.providerName
    var providerBaseUrl by SocialProviders.providerBaseUrl
    var providerIcon by SocialProviders.providerIcon
    
}