package fr.ftnl.texto.database.models

import com.sun.jna.platform.unix.solaris.LibKstat.KstatNamed.UNION.STR
import fr.ftnl.texto.database.abstract.BaseIntEntity
import fr.ftnl.texto.database.abstract.BaseIntEntityClass
import fr.ftnl.texto.database.abstract.BaseIntIdTable
import fr.ftnl.texto.ext.keyGen
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction

object AuthorTable : BaseIntIdTable("TBL_AUTHOR_AUT") {
    val discordId = long("discord_user_id").uniqueIndex()
    val name = varchar("name", 25)
    val avatarUrl = varchar("avatar", 255)
    val apiKey = varchar("api_key", 255)
}

class Author(id: EntityID<Int>): BaseIntEntity(id, AuthorTable) {
    companion object : BaseIntEntityClass<Author>(AuthorTable) {
        fun get(id: Long) = transaction {
            find { AuthorTable.discordId eq id }.firstOrNull()
        }
        fun get(key: String) = transaction {
            find { AuthorTable.apiKey eq key }.firstOrNull()
        }

        fun create(did: Long, name: String, avatar: String?) = transaction {
            new {
                _discordId = did
                _name = name
                _avatarUrl = avatar ?: "/static/images/Unknown_person.jpg"
                _apiKey = String.keyGen(255)
            }
        }
    }


    private var _discordId by AuthorTable.discordId
    val discordId
        get() = transaction { _discordId }

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
