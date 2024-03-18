package fr.ftnl.texto.database.models

import fr.ftnl.texto.database.abstract.BaseIntEntity
import fr.ftnl.texto.database.abstract.BaseIntEntityClass
import fr.ftnl.texto.database.abstract.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.jodatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

object TextoTable : BaseIntIdTable("TBL_TEXTO_TXT") {
    val user = reference("user", AuthorTable,
        onUpdate = ReferenceOption.CASCADE,
        onDelete = ReferenceOption.CASCADE
    )
    val keyHash = varchar("key_hash", 255).uniqueIndex()
    val name = varchar("name", 100)
    val description = text("description")
    val views = integer("vues").default(0)
    val public = bool("public").default(false)
    val expireAt = datetime("expire_at").nullable()
}

class Texto(id: EntityID<Int>): BaseIntEntity(id, TextoTable) {
    companion object : BaseIntEntityClass<Texto>(TextoTable) {
        fun get(id: String) = transaction { find { TextoTable.keyHash eq id }.firstOrNull() }
        fun create(
            author: Author,
            name: String,
            description : String,
            expire: DateTime? = DateTime.now().plusDays(7),
            hash: String,
            public: Boolean
        ) = transaction {
            new {
                _author = author
                _name = name
                _description = description
                _expireAt = expire
                _keyHash = hash
                _public = public
            }
        }
    }

    private var _author by Author referencedOn TextoTable.user
    val author
        get() = transaction { _author }


    private var _views by TextoTable.views
    var views: Int
        get() = transaction { _views }
        set(value) = transaction { _views = value }

    private var _name by TextoTable.name
    val name: String
        get() = transaction { _name }

    private var _description by TextoTable.description
    val description: String
        get() = transaction { _description }

    private var _keyHash by TextoTable.keyHash
    val keyHash: String
        get() = transaction { _keyHash }

    private var _public by TextoTable.public
    var public: Boolean
        get() = transaction { _public }
        set(value) = transaction { _public = value }

    private var _expireAt by TextoTable.expireAt
    val expireAt: DateTime?
        get() = transaction { _expireAt }

    fun deleteOnTransaction() = transaction { this@Texto.delete() }

}
