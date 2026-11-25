package mc.xalitoria.redeem.paper.data.db

import com.xalitoria.util.config.DatabaseConfig
import com.xalitoria.util.config.databaseType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mc.xalitoria.redeem.paper.XaliRedeem
import mc.xalitoria.redeem.paper.XaliRedeem.Companion.debug
import mc.xalitoria.redeem.paper.data.db.tables.HoldingTable
import mc.xalitoria.redeem.paper.data.db.tables.PendingRewardTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

/** HikariCP pool and Exposed [Database] for the plugin's tables (see [mc.xalitoria.redeem.paper.data.db.tables]). */
object DatabaseManager {
    lateinit var dataSource: HikariDataSource
        private set
    lateinit var database: Database
        private set

    var tablePrefix = "xaliredeem_"
        private set

    fun tableName(name: String): String = tablePrefix + name

    fun connect(plugin: XaliRedeem, config: DatabaseConfig) {
        val hikariConfig = HikariConfig()
        val type = config.databaseType
        if (type.isRemote) {
            val mysql = config.mysql
            hikariConfig.jdbcUrl = mysql.jdbcUrl(type)
            hikariConfig.driverClassName = mysql.driverClass(type)
            hikariConfig.username = mysql.username
            hikariConfig.password = mysql.password
            hikariConfig.maximumPoolSize = mysql.poolSize.coerceAtLeast(1)
        } else {
            val file = plugin.dataFolder.resolve(config.sqlite.filename)
            file.parentFile.mkdirs()
            hikariConfig.jdbcUrl = "jdbc:sqlite:${file.absolutePath}"
            hikariConfig.driverClassName = "org.sqlite.JDBC"
            // Single-writer: SQLite does not support real concurrent writes.
            hikariConfig.maximumPoolSize = 1
        }
        hikariConfig.poolName = "XaliRedeem-DB"
        attach(HikariDataSource(hikariConfig), config)
    }

    /** Sets up the schema on [source]. The prefix is set first, as table objects read it once. */
    internal fun attach(source: HikariDataSource, config: DatabaseConfig) {
        dataSource = source
        tablePrefix = DatabaseConfig.checkPrefix(config.tablePrefix)
        database = Database.connect(source)
        debug("Attaching database: prefix='{0}'", tablePrefix)

        transaction(database) {
            MigrationUtils.statementsRequiredForDatabaseMigration(HoldingTable, PendingRewardTable, withLogs = false)
                .onEach { debug("Schema statement: {0}", it) }
                .forEach { exec(it) }
        }
    }

    fun close() {
        if (::dataSource.isInitialized) dataSource.close()
    }

    /** Runs [statement] in a suspending transaction on [Dispatchers.IO]; nested calls join the outer one. */
    suspend fun <T> dbTransaction(statement: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = database, statement = statement) }
}
