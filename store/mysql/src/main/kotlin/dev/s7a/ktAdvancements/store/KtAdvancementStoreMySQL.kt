package dev.s7a.ktAdvancements.store

import dev.s7a.ktAdvancements.KtAdvancement
import dev.s7a.ktAdvancements.KtAdvancementStore
import org.bukkit.entity.Player
import java.sql.DriverManager

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
class KtAdvancementStoreMySQL(
    private val host: String,
    private val port: Int,
    private val database: String,
    private val username: String,
    private val password: String,
    private val tableName: String = "advancement_progress",
    private val options: Map<String, String> = emptyMap(),
) : KtAdvancementStore {
    init {
        // Check driver
        Class.forName("com.mysql.cj.jdbc.Driver")
    }

    private val connection by lazy {
        val url =
            buildString {
                append("jdbc:mysql://$host:$port/$database")
                if (options.isNotEmpty()) {
                    append("?")
                    append(options.entries.joinToString("&") { "${it.key}=${it.value}" })
                }
            }
        DriverManager.getConnection(url, username, password)
    }

    /**
     * Initializes the MySQL database
     *
     * Creates the necessary tables if they don't exist
     */
    fun onEnable() {
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

    override fun getProgress(
        player: Player,
        advancement: KtAdvancement,
    ): Int {
        connection
            .prepareStatement(
                """
                SELECT `progress` FROM `$tableName` WHERE `advancementId` = ? AND `playerUniqueId` = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, advancement.id.toString())
                statement.setString(2, player.uniqueId.toString())
                statement.executeQuery().use { result ->
                    return if (result.next()) {
                        result.getInt("progress")
                    } else {
                        0
                    }
                }
            }
    }

    override fun setProgress(
        player: Player,
        advancement: KtAdvancement,
        progress: Int,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO `$tableName` (`advancementId`, `playerUniqueId`, `progress`)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE `progress` = VALUES(`progress`)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, advancement.id.toString())
                stmt.setString(2, player.uniqueId.toString())
                stmt.setInt(3, progress)
                stmt.executeUpdate()
            }
    }

    /**
     * Closes the MySQL database connection
     *
     * Should be called when the plugin is disabled
     */
    fun onDisable() {
        connection.close()
    }
}
