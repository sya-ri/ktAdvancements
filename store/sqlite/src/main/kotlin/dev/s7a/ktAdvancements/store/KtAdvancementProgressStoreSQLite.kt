package dev.s7a.ktAdvancements.store

import dev.s7a.ktAdvancements.KtAdvancement
import dev.s7a.ktAdvancements.KtAdvancementProgressStore
import org.bukkit.entity.Player
import org.sqlite.SQLiteConfig
import java.io.File
import java.nio.file.Path
import java.sql.DriverManager

class KtAdvancementProgressStoreSQLite(
    private val file: File,
    private val config: SQLiteConfig = SQLiteConfig(),
) : KtAdvancementProgressStore {
    constructor(path: String, config: SQLiteConfig = SQLiteConfig()) : this(File(path), config)

    constructor(path: Path, config: SQLiteConfig = SQLiteConfig()) : this(path.toFile(), config)

    init {
        // Check driver
        Class.forName("org.sqlite.JDBC")
    }

    private val connection by lazy {
        DriverManager.getConnection("jdbc:sqlite:$file", config.toProperties())
    }

    fun onEnable() {
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

    override fun getProgress(
        player: Player,
        advancement: KtAdvancement,
    ): Int {
        connection
            .prepareStatement(
                """
                SELECT progress FROM advancement_progress WHERE advancementId = ? AND playerUniqueId = ?
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
                INSERT INTO advancement_progress (advancementId, playerUniqueId, progress)
                VALUES (?, ?, ?)
                ON CONFLICT(advancementId, playerUniqueId)
                DO UPDATE SET progress = excluded.progress
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, advancement.id.toString())
                stmt.setString(2, player.uniqueId.toString())
                stmt.setInt(3, progress)
                stmt.executeUpdate()
            }
    }

    fun disable() {
        connection.close()
    }
}
