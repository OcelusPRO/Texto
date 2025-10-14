package fr.ftnl.texto.database

import fr.ftnl.texto.database.models.*
import org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase


/**
 * Initializes the database.
 * @author Ocelus
 * @since 1.0.0
 */
@OptIn(ExperimentalDatabaseMigrationApi::class)
class DBManager() {
    
    private val url = System.getenv("DATABASE_URL")
    private val user = System.getenv("DATABASE_USER")
    private val password = System.getenv("DATABASE_PASSWORD")

  
    init {
        R2dbcDatabase.connect(
            url = "r2dbc:postgresql://$url",
            user =  user,
            password = password,
            driver = "postgresql",
        )
        
        transaction {
            val statements = MigrationUtils.statementsRequiredForDatabaseMigration(*DatabaseRegistry.tables)
            this.execInBatch(statements)
        }
    }

}