package fr.ftnl.texto.database.abstract

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.jodatime.CurrentDateTime
import org.jetbrains.exposed.sql.jodatime.datetime
import org.joda.time.DateTime



val dbPrefix: String = System.getenv("DATABASE_PREFIX") ?: ""

/**
 * Base class for all tables with an integer id.
 *
 * @param name [String] The name of the table.
 *
 * @property createdAt [Column] The creation date of the entity.
 * @property updatedAt [Column] The last update date of the entity.
 */
open class BaseIntIdTable(name: String) : IntIdTable(dbPrefix + name) {
    val createdAt: Column<DateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt: Column<DateTime> = datetime("updated_at").defaultExpression(CurrentDateTime)
}