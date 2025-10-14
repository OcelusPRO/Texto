package fr.ftnl.texto.database.types

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.dao.EntityChangeType
import org.jetbrains.exposed.v1.dao.EntityHook
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.dao.toEntity
import kotlin.time.Clock
import kotlin.time.ExperimentalTime


/**
 * Base class for all entities with an integer id.
 *
 * @param table [BaseIntIdTable] The table of the entity.
 */
@OptIn(ExperimentalTime::class)
open class BaseIntEntityClass<E : BaseIntEntity>(table: BaseIntIdTable) : IntEntityClass<E>(table) {
    init {
        EntityHook.subscribe { change ->
            val changedEntity = change.toEntity(this)
            when (change.changeType) {
                EntityChangeType.Updated -> changedEntity?.let { it.updatedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()) }
                else                     -> {}
            }
        }
    }
}
