package fr.ftnl.texto.database.abstract

import org.jetbrains.exposed.dao.EntityChangeType
import org.jetbrains.exposed.dao.EntityHook
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.toEntity
import org.joda.time.DateTime

/**
 * Base class for all entities with an integer id.
 *
 * @param table [BaseIntIdTable] The table of the entity.
 */
open class BaseIntEntityClass<E : BaseIntEntity>(table: BaseIntIdTable) : IntEntityClass<E>(table) {
    init {
        EntityHook.subscribe { action ->
            if (action.changeType == EntityChangeType.Updated) {
                try { action.toEntity(this)?.updatedAt = DateTime.now()
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }
}