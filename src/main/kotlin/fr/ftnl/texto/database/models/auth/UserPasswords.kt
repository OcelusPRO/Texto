package fr.ftnl.texto.database.models.auth

import fr.ftnl.texto.database.models.User
import fr.ftnl.texto.database.models.Users
import fr.ftnl.texto.database.types.BaseIntEntity
import fr.ftnl.texto.database.types.BaseIntEntityClass
import fr.ftnl.texto.database.types.BaseIntIdTable
import fr.ftnl.texto.ext.secureHash
import org.jetbrains.exposed.v1.core.dao.id.EntityID

object UserPasswords : BaseIntIdTable("TBL_USERS_PASSWORDS_UPW") {
    val user = reference("user", Users).uniqueIndex()
    val hash = varchar("hash", 255)
    val salt = varchar("salt", 255)
}


class UserPassword(id: EntityID<Int>): BaseIntEntity(id, UserPasswords) {
    
    companion object : BaseIntEntityClass<UserPassword>(UserPasswords) {
        fun create(user: User, password: String): UserPassword {
            val hashed = password.secureHash()
            return new {
                _user = user
                this.passwordHash = hashed.hash
                this.salt = hashed.salt
            }
        }
    }
    
    private var _user by User.Companion referencedOn UserPasswords.user
    val user: User get() = _user
    
    var passwordHash by UserPasswords.hash
    var salt by UserPasswords.salt
    
    fun verifyPassword(password: String) = this.passwordHash == password.secureHash(this.salt).hash
    
    
}