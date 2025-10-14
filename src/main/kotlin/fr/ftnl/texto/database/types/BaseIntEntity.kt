package fr.ftnl.texto.database.types

import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.jdbc.transactions.transaction


/**
 * Base class for all entities with an integer id.
 *
 * @param id [EntityID] The id of the entity.
 *
 * @property createdAt [DateTime] The creation date of the entity.
 * @property updatedAt [DateTime] The last update date of the entity.
 */
open class BaseIntEntity(id: EntityID<Int>, table: BaseIntIdTable) : IntEntity(id) {
    private val _createdAt: LocalDateTime by table.createdAt
    val createdAt: LocalDateTime
        get() = transaction { _createdAt }

    private var _updatedAt: LocalDateTime by table.updatedAt
    var updatedAt: LocalDateTime
        get() = transaction { _updatedAt }
        set(value) = transaction { _updatedAt = value }
}