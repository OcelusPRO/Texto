package fr.ftnl.texto.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import fr.ftnl.texto.database.models.AuthorTable
import fr.ftnl.texto.database.models.SocialMediaTable
import fr.ftnl.texto.database.models.TextoTable
import io.ktor.server.config.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction


/**
 * Initializes the database.
 * @author Ocelus
 * @since 1.0.0
 */
class DBManager(config: ApplicationConfig) {

    private fun getDriverName(type: String): String = when(type) {
        "mssql" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver"
        "h2" -> "org.h2.Driver"
        //"sqlite" -> "org.sqlite.JDBC"
        "mariadb" -> "org.mariadb.jdbc.Driver"
        "mysql" -> "com.mysql.cj.jdbc.Driver"
        "pgsql" -> "com.impossibl.postgres.jdbc.PGDriver"
        "postgresql" -> "org.postgresql.Driver"
        else -> ""
    }
    private fun getDbUrl(config: ApplicationConfig): String {
        val host = config.property("database.host").getString()
        val name = config.propertyOrNull("database.name")?.getString() ?: ""

        return when(config.property("database.type").getString()){
            "mssql" -> "jdbc:sqlserver://$host${if (name.isNotBlank()) ";databaseName=$name" else ""}"
            "h2" -> "jdbc:h2:$host"
            "mariadb" -> "jdbc:mariadb://$host/$name"
            "mysql" -> "jdbc:mysql://$host/$name"
            "pgsql" -> "jdbc:pgsql://$host/$name"
            "postgresql" -> "jdbc:postgresql://$host/$name"
            else -> ""
        }
    }

    private fun getHikariConfig(config: ApplicationConfig): HikariConfig = HikariConfig().apply {
        jdbcUrl = getDbUrl(config)
        driverClassName = getDriverName(config.property("database.type").getString())
        username = config.propertyOrNull("database.user")?.getString()
        password = config.propertyOrNull("database.pass")?.getString()
        maximumPoolSize = config.property("database.poolSize").getString().toInt()
        isReadOnly = false
        transactionIsolation = "TRANSACTION_SERIALIZABLE"
    }

    init {
        if (config.property("database.hikari").getString() == "true")  {
            val hikariDataSource = HikariDataSource(getHikariConfig(config))
            Database.connect(datasource = hikariDataSource)
        } else {
            Database.connect(
                url = getDbUrl(config),
                driver = getDriverName(config.property("database.type").getString()),
                user =  config.property("database.user").getString(),
                password = config.property("database.pass").getString(),
            )
        }

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                AuthorTable,
                SocialMediaTable,
                TextoTable
            )
        }
    }
}