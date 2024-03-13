package fr.ftnl.texto.database.models

import com.github.benmanes.caffeine.cache.Expiry
import fr.ftnl.texto.database.abstract.BaseIntEntity
import fr.ftnl.texto.database.abstract.BaseIntEntityClass
import fr.ftnl.texto.database.abstract.BaseIntIdTable
import fr.ftnl.texto.ext.md5
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.jodatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

object TextoTable : BaseIntIdTable("TBL_TEXTO_TXT") {
    val user = reference("user", AuthorTable)
    val keyHash = varchar("key_hash", 255).uniqueIndex()
    val name = varchar("name", 100)
    val description = text("description")
    val vues = integer("vues").default(0)
    val expireAt = datetime("expire_at").nullable()
}

class Texto(id: EntityID<Int>): BaseIntEntity(id, TextoTable) {
    companion object : BaseIntEntityClass<Texto>(TextoTable) {
        fun get(id: String) = transaction { find { TextoTable.keyHash eq id }.firstOrNull() }
    }

    private var _author by Author referencedOn TextoTable.user
    val author
        get() = transaction { _author }


    private var _vues by TextoTable.vues
    val vues: Int
        get() = transaction { _vues }

    private var _name by TextoTable.name
    val name: String
        get() = transaction { _name }

    private var _description by TextoTable.description
    val description: String
        get() = transaction { _description }

    private var _keyHash by TextoTable.keyHash
    val keyHash: String
        get() = transaction { _keyHash }

    private var _expireAt by TextoTable.expireAt
    val expireAt: DateTime?
        get() = transaction { _expireAt }

}
