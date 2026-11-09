package fr.ftnl.texto.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import fr.ftnl.texto.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import javax.sql.DataSource

object Db {
    lateinit var dataSource: DataSource
        private set
    lateinit var database: Database
        private set

    fun init(cfg: AppConfig.DbConfig) {
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = cfg.url
            username = cfg.user
            password = cfg.password
            maximumPoolSize = cfg.poolSize
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        })
        init(ds)
    }

    fun init(ds: DataSource) {
        dataSource = ds
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            // La base de la v1 n'est pas vide : on démarre l'historique Flyway à 0 pour jouer V1.
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .table("t_flyway_history")
            .load()
            .migrate()
        database = Database.connect(ds)
    }
}

/** Exécute [block] dans une transaction, sur le dispatcher IO. */
suspend fun <T> db(block: JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) { transaction(Db.database) { block() } }

fun <T> dbBlocking(block: JdbcTransaction.() -> T): T = transaction(Db.database) { block() }
