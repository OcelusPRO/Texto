package fr.ftnl.texto.database.models.auth

import fr.ftnl.texto.database.types.BaseIntEntity
import fr.ftnl.texto.database.types.BaseIntEntityClass
import fr.ftnl.texto.database.types.BaseIntIdTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.crypt.Algorithms
import org.jetbrains.exposed.v1.crypt.encryptedVarchar


private val aesKey = System.getenv("DB_AES_KEY")
private val aesSalt = System.getenv("DB_AES_SALT")

object OAuthProviders : BaseIntIdTable("TBL_OAUTH_PROVIDERS_PRV") {
    val providerName = varchar("providerName", 255).uniqueIndex()
    val providerBaseUrl = text("providerBaseUrl")
    val providerConnectPath = text("providerConnectPath")
    val providerTokenPath = text("providerTokenPath")
    val providerClientId = varchar("providerClientId", 255)
    val providerClientSecret = encryptedVarchar("providerClientSecret", 255, Algorithms.AES_256_PBE_GCM(aesKey, aesSalt))
    val scopes = array<String>("scopes").default(emptyList())
    
}


class OAuthProvider(id: EntityID<Int>): BaseIntEntity(id, OAuthProviders) {
    
    companion object : BaseIntEntityClass<OAuthProvider>(OAuthProviders) {
        fun getByName(name: String) = find { OAuthProviders.providerName eq name }.firstOrNull()
        fun create(
            name: String,
            baseUrl: String,
            connectPath: String,
            tokenPath: String,
            clientId: String,
            clientSecret: String,
            requiredScopes: List<String>
        ) = new {
            _providerName = name
            providerBaseUrl = baseUrl
            providerConnectPath = connectPath
            providerTokenPath = tokenPath
            providerClientId = clientId
            providerClientSecret = clientSecret
            scopes = requiredScopes
        }
        fun createIfNotExists(
            name: String,
            baseUrl: String,
            connectPath: String,
            tokenPath: String,
            clientId: String,
            clientSecret: String,
            requiredScopes: List<String>
        ) = getByName(name) ?: create(name, baseUrl, connectPath, tokenPath, clientId, clientSecret, requiredScopes)
    }
    
    private var _providerName by OAuthProviders.providerName
    val providerName: String get() = _providerName
    
    var providerConnectPath by OAuthProviders.providerConnectPath
    var providerTokenPath by OAuthProviders.providerTokenPath
    var providerBaseUrl by OAuthProviders.providerBaseUrl
    var providerClientId by OAuthProviders.providerClientId
    var providerClientSecret by OAuthProviders.providerClientSecret
    var scopes by OAuthProviders.scopes
    
    val profils by OAuthProfil referrersOn OAuthProfils.provider
    
    fun getProfil(profilId: String) = OAuthProfil.getByProfileIdAndProvider(profilId, this)
}
