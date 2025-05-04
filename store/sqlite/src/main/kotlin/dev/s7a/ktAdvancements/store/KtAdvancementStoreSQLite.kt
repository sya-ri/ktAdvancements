package dev.s7a.ktAdvancements.store

import dev.s7a.ktAdvancements.KtAdvancement
import dev.s7a.ktAdvancements.KtAdvancementStore
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.sqlite.SQLiteConfig
import java.io.File
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.logging.Logger

/**
 * SQLite implementation of [KtAdvancementStore]
 *
 * @property file SQLite database file
 * @property config SQLite configuration
 */
class KtAdvancementStoreSQLite<T : KtAdvancement<T>>(
    private val file: File,
    private val config: SQLiteConfig = SQLiteConfig(),
) : KtAdvancementStore<T> {
    /**
     * Creates a new SQLite store with the specified path
     *
     * @param path Path to the SQLite database file
     * @param config SQLite configuration
     */
    constructor(path: String, config: SQLiteConfig = SQLiteConfig()) : this(File(path), config)

    /**
     * Creates a new SQLite store with the specified path
     *
     * @param path Path to the SQLite database file
     * @param config SQLite configuration
     */
    constructor(path: Path, config: SQLiteConfig = SQLiteConfig()) : this(path.toFile(), config)

    private val logger = Logger.getLogger("KtAdvancementStoreSQLite")

    init {
        // Check driver
        Class.forName("org.sqlite.JDBC")
    }

    private fun getConnection() = DriverManager.getConnection("jdbc:sqlite:$file", config.toProperties())

    /**
     * Initializes the SQLite database
     *
     * Creates the necessary tables if they don't exist
     */
    fun setup() {
        getConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS advancement_progress (
                        advancementId TEXT NOT NULL,
                        playerUniqueId TEXT NOT NULL,
                        progress INTEGER NOT NULL,
                        PRIMARY KEY (advancementId, playerUniqueId)
                    );
                    """.trimIndent(),
                )
            }
        }
    }

    override fun getProgress(
        player: Player,
        advancements: List<T>,
    ): Map<T, Int> {
        getConnection().use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT advancementId, progress FROM advancement_progress WHERE playerUniqueId = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, player.uniqueId.toString())
                    statement.executeQuery().use { result ->
                        val map = advancements.associateBy(KtAdvancement<*>::id)
                        return buildMap {
                            while (result.next()) {
                                val advancementId = NamespacedKey.fromString(result.getString("advancementId"))
                                val advancement = map[advancementId]
                                if (advancement != null) {
                                    put(advancement, result.getInt("progress"))
                                } else {
                                    logger.warning("Invalid advancementId: ${result.getString("advancementId")}")
                                }
                            }
                        }
                    }
                }
        }
    }

    override fun updateProgress(
        player: Player,
        progress: Map<T, Int>,
    ) {
        if (progress.isEmpty()) return
        getConnection().useTransaction { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO advancement_progress (advancementId, playerUniqueId, progress)
                    VALUES (?, ?, ?)
                    ON CONFLICT(advancementId, playerUniqueId)
                    DO UPDATE SET progress = excluded.progress
                    """.trimIndent(),
                ).use { statement ->
                    progress.forEach { (advancement, value) ->
                        statement.setString(1, advancement.id.toString())
                        statement.setString(2, player.uniqueId.toString())
                        statement.setInt(3, value)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
        }
    }

    private inline fun <R> Connection.useTransaction(block: (connection: Connection) -> R) =
        use { connection ->
            connection.autoCommit = false
            try {
                return@use block(connection).apply {
                    connection.commit()
                }
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
}
