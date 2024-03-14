package fr.ftnl.texto.database.models

import fr.ftnl.texto.database.abstract.BaseIntEntity
import fr.ftnl.texto.database.abstract.BaseIntEntityClass
import fr.ftnl.texto.database.abstract.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction

object AuthorTable : BaseIntIdTable("TBL_AUTHOR_AUT") {
    val discordId = long("discord_user_id").uniqueIndex()
    val name = varchar("name", 25)
    val avatarUrl = varchar("avatar", 255)
}

class Author(id: EntityID<Int>): BaseIntEntity(id, AuthorTable) {
    companion object : BaseIntEntityClass<Author>(AuthorTable) {

    }


    private var _discordId by AuthorTable.discordId
    val discordId
        get() = transaction { _discordId }

    private var _name by AuthorTable.name
    val name: String
        get() = transaction { _name }

    private var _avatarUrl by AuthorTable.avatarUrl
    val avatarUrl: String
        get() = transaction { _avatarUrl }

    private val _social by SocialMedia referrersOn SocialMediaTable.user
    val social
        get() = transaction { _social.toList() }


    private val _texto by Texto referrersOn TextoTable.user
    val textos
        get() = transaction { _texto.toList() }
}
