package fr.ftnl.texto.database.models

import fr.ftnl.texto.database.abstract.BaseIntEntity
import fr.ftnl.texto.database.abstract.BaseIntEntityClass
import fr.ftnl.texto.database.abstract.BaseIntIdTable
import fr.ftnl.texto.plugins.security.modules.Argon2Conf
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.security.MessageDigest


object UserLoginTable : BaseIntIdTable("TBL_USER_LOGIN_ULO") {
    val email = varchar("user_email_hash", 128).uniqueIndex()
    val username = varchar("username-hash", 128).uniqueIndex()
    val password = varchar("password", 512)

    val author = reference("author", AuthorTable)
}

class UserLogin(id: EntityID<Int>): BaseIntEntity(id, UserLoginTable) {
    companion object : BaseIntEntityClass<UserLogin>(UserLoginTable) {

        fun createUser(username: String, email: String, password: String, conf: Argon2Conf) = transaction {
            val psw = hashPassword(password, conf)

            val mail = hashSha3512(email.lowercase())
            val user = hashSha3512(username.lowercase())

            val author = Author.getByEmail(email) ?: Author.create(email, username, null)

            new {
                _email = mail
                _password = psw
                _name = user

                _author = author
            }
        }


        fun get(identifier: String): UserLogin? = transaction {
            UserLogin.find { UserLoginTable.email eq hashSha3512(identifier.lowercase()) }.firstOrNull()
                ?: UserLogin.find { UserLoginTable.username eq hashSha3512(identifier.lowercase()) }.firstOrNull()
        }


    }

    private var _email by UserLoginTable.email
    val emailHash
        get() = transaction { _email }

    private var _name by UserLoginTable.username
    var name: String
        get() = transaction { _name }
        set(value) = transaction { _name = value }

    private var _password by UserLoginTable.password
    var password: String
        get() = transaction { _password }
        set(value) = transaction { _password = value }


    private var _author by Author referencedOn UserLoginTable.author
    val author
        get() = transaction { _author }

}


private fun hashPassword(password: String, conf: Argon2Conf): String{
    val arg2SpringSecurity = Argon2PasswordEncoder(
        conf.saltLength,
        conf.hashLength,
        conf.parallelism,
        conf.memory,
        conf.iterator
    )
    return arg2SpringSecurity.encode(password)
}

fun hashSha3512(message:String): String {
    val md = MessageDigest.getInstance("SHA3-512")
    val digest = md.digest(message.toByteArray())
    val sb = StringBuilder()
    for (i in digest.indices) { sb.append(((digest[i].toInt() and 0xff) + 0x100).toString(16).substring(1)) }
    return sb.toString()
}