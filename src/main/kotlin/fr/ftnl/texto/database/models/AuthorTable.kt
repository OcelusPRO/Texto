package fr.ftnl.texto.database.models

import fr.ftnl.texto.database.abstract.BaseIntEntity
import fr.ftnl.texto.database.abstract.BaseIntEntityClass
import fr.ftnl.texto.database.abstract.BaseIntIdTable
import fr.ftnl.texto.ext.keyGen
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction

object AuthorTable : BaseIntIdTable("TBL_AUTHOR_AUT") {
    val email = integer("user_email_hash").uniqueIndex()
    val name = varchar("name", 25)
    val avatarUrl = varchar("avatar", 255)
    val apiKey = varchar("api_key", 255)
}

class Author(id: EntityID<Int>): BaseIntEntity(id, AuthorTable) {
    companion object : BaseIntEntityClass<Author>(AuthorTable) {
        fun getByEmail(email: String) = transaction {
            find { AuthorTable.email eq email.hashCode() }.firstOrNull()
        }
        fun getByApiKey(key: String) = transaction {
            find { AuthorTable.apiKey eq key }.firstOrNull()
        }

        fun create(email: String, name: String, avatar: String?) = transaction {
            new {
                _email = email.hashCode()
                _name = name
                _avatarUrl = avatar ?: "/static/images/Unknown_person.jpg"
                _apiKey = String.keyGen(255)
            }
        }
    }


    private var _email by AuthorTable.email
    val emailHash
        get() = transaction { _email }

    private var _name by AuthorTable.name
    var name: String
        get() = transaction { _name }
        set(value) = transaction { _name = value }

    private var _avatarUrl by AuthorTable.avatarUrl
    var avatarUrl: String
        get() = transaction { _avatarUrl }
        set(value) = transaction { _avatarUrl = value }

    private var _apiKey by AuthorTable.apiKey
    var apiKey: String
        get() = transaction { _apiKey }
        set(value) = transaction { _apiKey = value }

    private val _social by SocialMedia referrersOn SocialMediaTable.user
    val social
        get() = transaction { _social.toList() }


    private val _texto by Texto referrersOn TextoTable.user
    val textos
        get() = transaction { _texto.toList() }
}
