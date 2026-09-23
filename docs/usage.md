# Usage

Install the [API and a matching runtime](../README.md#installation) first. The [example plugin](../example) shows these definitions and lifecycle calls together.

## Creating an Advancement

```kotlin
enum class Advancement(
    override val parent: Advancement?,
    x: Float,
    y: Float,
    icon: Material,
    title: String,
    description: String,
    frame: KtAdvancement.Display.Frame = KtAdvancement.Display.Frame.Task,
    override val requirement: Int = 1,
    override val visibility: KtAdvancement.Visibility = KtAdvancement.Visibility.Always,
    override val defaultGranted: Boolean = false,
) : KtAdvancement<Advancement> {
    HelloWorld(null, 0F, 3F, Material.GRASS_BLOCK, "Hello world", "Join the server"),
    MineStone(HelloWorld, 0F, 1.5F, Material.STONE, "Mine stone", "Mine 10 stones", requirement = 10),
    ;

    @Suppress("DEPRECATION")
    override val id: NamespacedKey
        get() = NamespacedKey("example", name.lowercase())

    override val display: KtAdvancement.Display =
        if (parent != null) {
            KtAdvancement.Display(
                parent.display.x + x,
                parent.display.y + y,
                ItemStack(icon),
                title,
                description,
                frame = frame,
            )
        } else {
            KtAdvancement.Display(
                x,
                y,
                ItemStack(icon),
                title,
                description,
                frame = frame,
                background = NamespacedKey.minecraft("textures/gui/advancements/backgrounds/adventure.png"),
            )
        }
}
```

<a id="-about-progress-management"></a>

### About Progress Management

- The `requirement` parameter represents the number of steps needed to complete the advancement
- Internally, criteria are created as base-36 strings for each step
- Due to packet size limitations, it's recommended to keep the `requirement` value small
- While vanilla Minecraft allows custom criteria strings, this library uses a simplified numeric step system for better performance

<a id="️-about-visibility"></a>

### About Visibility

The library provides several visibility options:

- `Always`: Always visible
- `HaveProgress`: Visible when player has any progress
- `Granted`: Visible only when advancement is granted
- `ParentGranted`: Visible when the parent advancement is granted, or when there is no parent
- `Any`: Visible when any of the specified conditions are met
- `All`: Visible when all specified conditions are met

You can also create your own visibility class by implementing `KtAdvancement.Visibility`:

```kotlin
class CustomVisibility : KtAdvancement.Visibility {
    override fun <T : KtAdvancement<T>> isShow(
        advancement: T,
        store: KtAdvancementStore<T>,
        player: Player,
    ): Boolean {
        TODO("Your custom visibility logic here")
    }
}
```

## Managing Advancements

```kotlin
// Initialize KtAdvancements (runtime will be automatically selected based on version)
val ktAdvancements = KtAdvancements(Advancement.entries, KtAdvancementStore.InMemory())

// Show all advancements to player (call this when player joins the server)
ktAdvancements.showAll(player)

// Grant advancement to player (complete all steps)
ktAdvancements.grant(player, advancement)

// Grant all advancements to player
ktAdvancements.grantAll(player)

// Add one step of progress
ktAdvancements.grant(player, advancement, step = 1)

// Revoke advancement from player (remove all progress)
ktAdvancements.revoke(player, advancement)

// Revoke all advancements from player
ktAdvancements.revokeAll(player)

// Remove one step of progress
ktAdvancements.revoke(player, advancement, step = 1)

// Set progress of advancement
ktAdvancements.set(player, advancement, progress = 3)

// Batch progress changes into one store update and one packet update
ktAdvancements.transaction(player) {
    grant(advancement1)
    revoke(advancement2, step = 5)
    set(advancement3, progress = 2)
}
```

When managing multiple advancements simultaneously, it's recommended to use `transaction` instead of individual method calls. Using `transaction` provides several benefits:

- Packet sending is optimized into a single operation
- Data store writes are optimized into a single operation

This reduces repeated storage and packet work. A transaction batches these updates;
it does not provide rollback across the data store and packet delivery.

## Data Storage

The library provides multiple storage options for advancement progress:

<a id="-ktadvancementstoreinmemory"></a>

### KtAdvancementStore.InMemory

Default in-memory data store:

```kotlin
val ktAdvancements = KtAdvancements(
    advancements,
    KtAdvancementStore.InMemory()
)
```

<a id="️-ktadvancementstoresqlite"></a>

### KtAdvancementStore.SQLite

Persistent data storage using SQLite with [SQLite JDBC](https://central.sonatype.com/artifact/org.xerial/sqlite-jdbc):

```kotlin
// Add dependency to your build.gradle.kts
dependencies {
    implementation("dev.s7a:ktAdvancements-store-sqlite:1.0.0")

    // SQLite JDBC driver is bundled with Spigot by default
    // Install if you need a different version
    // implementation("org.xerial:sqlite-jdbc:{VERSION}")
}
```

```kotlin
// Initialize with database path
val ktAdvancements = KtAdvancements(
    advancements,
    KtAdvancementStoreSQLite("path/to/database.db")
)

// Create a table
ktAdvancements.store.setup()
```

<a id="️-ktadvancementstoremysql"></a>

### KtAdvancementStore.MySQL

Persistent data storage using MySQL with [MySQL Connector/J](https://central.sonatype.com/artifact/com.mysql/mysql-connector-j):

```kotlin
// Add dependency to your build.gradle.kts
dependencies {
    implementation("dev.s7a:ktAdvancements-store-mysql:1.0.0")
    implementation("com.mysql:mysql-connector-j:{VERSION}")
}
```

```kotlin
// Initialize with MySQL connection details
val ktAdvancements = KtAdvancements(
    advancements,
    KtAdvancementStoreMySQL(
        host = "localhost",
        port = 3306,
        database = "minecraft",
        username = "root",
        password = "password",
        tableName = "advancement_progress", // optional
        options = mapOf( // optional
            "useSSL" to "false",
            "serverTimezone" to "UTC",
            "characterEncoding" to "utf8mb4"
        )
    )
)

// Create a table
ktAdvancements.store.setup()
```

<a id="-custom-storage"></a>

### Custom Storage

You can create your own data store by implementing `KtAdvancementStore`:

```kotlin
class CustomStore<T : KtAdvancement<T>> : KtAdvancementStore<T> {
    override fun getProgress(
        player: Player,
        advancements: List<T>,
    ): Map<T, Int> {
        TODO("Get progress from your custom storage")
    }

    override fun updateProgress(
        player: Player,
        progress: Map<T, Int>,
    ) {
        TODO("Save progress to your custom storage")
    }
}
```
