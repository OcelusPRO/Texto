package fr.ftnl.texto.database.types

import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime


/**
 * Base class for all tables with an integer id.
 *
 * @param name [String] The name of the table.
 *
 * @property createdAt [Column] The creation date of the entity.
 * @property updatedAt [Column] The last update date of the entity.
 */

val dbPrefix = System.getenv("DATABASE_PREFIX") ?: ""
open class BaseIntIdTable(name: String, withPrefix: Boolean = true) : IntIdTable((if (withPrefix) dbPrefix else "") + name) {
    val createdAt: Column<LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt: Column<LocalDateTime> = datetime("updated_at").defaultExpression(CurrentDateTime)
}