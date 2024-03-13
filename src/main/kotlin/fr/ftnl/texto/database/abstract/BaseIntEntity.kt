package fr.ftnl.texto.database.abstract

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

/**
 * Base class for all entities with an integer id.
 *
 * @param id [EntityID] The id of the entity.
 *
 * @property createdAt [DateTime] The creation date of the entity.
 * @property updatedAt [DateTime] The last update date of the entity.
 */
open class BaseIntEntity(id: EntityID<Int>, table: BaseIntIdTable) : IntEntity(id) {
    private val _createdAt: DateTime by table.createdAt
    val createdAt: DateTime
        get() = transaction { _createdAt }

    private var _updatedAt: DateTime by table.updatedAt
    var updatedAt: DateTime
        get() = transaction { _updatedAt }
        set(value) = transaction { _updatedAt = value }
}