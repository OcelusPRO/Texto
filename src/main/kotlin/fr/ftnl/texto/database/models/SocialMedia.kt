package fr.ftnl.texto.database.models

import fr.ftnl.texto.database.abstract.BaseIntEntity
import fr.ftnl.texto.database.abstract.BaseIntEntityClass
import fr.ftnl.texto.database.abstract.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.transactions.transaction

object SocialMediaTable : BaseIntIdTable("TBL_SOCIAL_MEDIA_SOM") {
    val user = reference("user", AuthorTable,
        onUpdate = ReferenceOption.CASCADE,
        onDelete = ReferenceOption.CASCADE
    )
    val iconName = varchar("icon", 100)
    val url = varchar("url", 255)
}

class SocialMedia(id: EntityID<Int>): BaseIntEntity(id, SocialMediaTable) {
    companion object : BaseIntEntityClass<SocialMedia>(SocialMediaTable) {
        fun new(user: Author, icon: String, url: String) = transaction {
            new {
                _author = user
                _iconName = icon
                _url = url
            }
        }
    }

    private var _author by Author referencedOn SocialMediaTable.user
    val author
        get() = transaction { _author }

    private var _iconName by SocialMediaTable.iconName
    val iconName: String
        get() = transaction { _iconName }

    private var _url by SocialMediaTable.url
    val url: String
        get() = transaction { _url }
    fun deleteOnTransaction() = transaction { this@SocialMedia.delete() }

}
