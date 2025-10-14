package fr.ftnl.texto.database.models

import fr.ftnl.texto.database.models.auth.UserPassword
import fr.ftnl.texto.database.models.auth.UserPasswords
import fr.ftnl.texto.database.models.social.UserSocials
import fr.ftnl.texto.database.types.BaseIntEntity
import fr.ftnl.texto.database.types.BaseIntEntityClass
import fr.ftnl.texto.database.types.BaseIntIdTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq

object Users : BaseIntIdTable("TBL_USERS_USR") {

    val userId = varchar("userId", 255)
    val discriminator = integer("discriminator").default(0)
    val username = varchar("username", 255)
    val avatar = varchar("avatar", 255)
    
    init {
        uniqueIndex("unique_user_id", userId, discriminator)
    }
}


class User(id: EntityID<Int>): BaseIntEntity(id, Users) {
    
    companion object : BaseIntEntityClass<User>(Users) {
        fun findByUserId(userId: String) = find { Users.userId eq userId }
        
        fun create(userId: String, username: String, avatar: String = "default.webp"): User {
            
            var discriminator = 0
            val othersWithSameId = findByUserId(userId)
            if (othersWithSameId.count() > 0) {
                val existingDiscriminators = othersWithSameId.map { it._discriminator }.toSet()
                for (i in 1..9999) if (!existingDiscriminators.contains(i) && discriminator == 0) discriminator = i
                if (discriminator == 0) throw Exception("No available discriminator for userId $userId")
            }
            
            return new {
                this._userId = userId
                this._discriminator = discriminator
                this.username = username
                this.avatar = avatar
            }
        }
        
    }
    
    private var _userId by Users.userId
    private var _discriminator by Users.discriminator
    val userId: String get() {
        val discriminate = if (_discriminator != 0) "#${_discriminator.toString().padStart(4, '0')}" else ""
        return "${_userId}${discriminate}"
    }
    var username by Users.username
    var avatar by Users.avatar
    
    private val _userPass by UserPassword referrersOn UserPasswords.user
    private fun userPass() = _userPass.firstOrNull()
    
    fun setPassword(password: String) {
        userPass()?.delete()
        UserPassword.create(this, password)
    }
    fun verifyPassword(password: String) = userPass()?.verifyPassword(password) ?: false
    
    val socials by referencedOn(UserSocials.user)
}