package dev.s7a.ktAdvancements.store

import dev.s7a.ktAdvancements.KtAdvancement
import dev.s7a.ktAdvancements.KtAdvancementStore
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import java.sql.Connection
import java.sql.DriverManager
import java.util.logging.Logger
import kotlin.use

/**
 * MySQL implementation of [KtAdvancementStore]
 *
 * @property host MySQL server host
 * @property port MySQL server port
 * @property database Database name
 * @property username Database username
 * @property password Database password
 * @property tableName Table name to store advancement progress
 * @property options Additional connection options
 */
class KtAdvancementStoreMySQL<T : KtAdvancement<T>>(
    private val host: String,
    private val port: Int,
    private val database: String,
    private val username: String,
    private val password: String,
    private val tableName: String = "advancement_progress",
    private val options: Map<String, String> = emptyMap(),
) : KtAdvancementStore<T> {
    private val logger = Logger.getLogger("KtAdvancementStoreMySQL")

    init {
        // Check driver
        Class.forName("com.mysql.cj.jdbc.Driver")
    }

    private fun getConnection() =
        DriverManager.getConnection(
            buildString {
                append("jdbc:mysql://$host:$port/$database")
                if (options.isNotEmpty()) {
                    append("?")
                    append(options.entries.joinToString("&") { "${it.key}=${it.value}" })
                }
            },
            username,
            password,
        )

    /**
     * Initializes the MySQL database
     *
     * Creates the necessary tables if they don't exist
     */
    fun setup() {
        getConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `$tableName` (
                        `advancementId` VARCHAR(255) NOT NULL,
                        `playerUniqueId` VARCHAR(36) NOT NULL,
                        `progress` INT NOT NULL,
                        PRIMARY KEY (`advancementId`, `playerUniqueId`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
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
                    SELECT `advancementId`, `progress` FROM `$tableName` WHERE `playerUniqueId` = ?
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
                    INSERT INTO `$tableName` (`advancementId`, `playerUniqueId`, `progress`)
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE `progress` = VALUES(`progress`)
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
