package dev.slne.surf.polarpaper.api.settings

import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.data.ChunkDataHandler
import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.world.GameModeIdentifiers
import dev.slne.surf.polarpaper.api.world.SurfChunk
import net.kyori.adventure.key.Key
import kotlin.time.Duration

class GameModeSettings(
    val identifier: String,
    val currentVersion: Int,
    val migrations: List<WorldMigration>
)

class PolarPaperSettings(
    val gameModes: Map<String, GameModeSettings>,
    val builtInHandlers: Set<BuiltInHandler>,
    val handlers: List<ChunkDataHandler<*>>,
    /** Plain sections set by gameplay code, never captured. */
    val dataTypes: List<DataType<*>>,
    /** Null means no autosave, which is the default. */
    val autosaveInterval: Duration?,
    /** Run on the tick thread while a chunk is loaded, after its handlers restored. */
    val chunkLoadHooks: List<(SurfChunk) -> Unit>,
    /** Run on the chunk's owning thread while the world is prepared for saving, before the handlers capture. */
    val chunkSaveHooks: List<(SurfChunk) -> Unit>,
)

@DslMarker
annotation class PolarPaperSettingsDsl

@PolarPaperSettingsDsl
class GameModeSettingsBuilder internal constructor(private val identifier: String) {
    var version: Int = 0
    private val migrations = mutableListOf<WorldMigration>()

    fun migration(migration: WorldMigration) {
        migrations += migration
    }

    internal fun build(): GameModeSettings {
        require(version >= 0) { "Game mode '$identifier' has a negative version $version" }

        migrations.groupBy { it.toVersion }.forEach { (to, list) ->
            require(list.size == 1) { "Game mode '$identifier' has ${list.size} migrations to version $to" }
            require(to <= version) {
                "Game mode '$identifier' has a migration to version $to above its current version $version"
            }
        }

        return GameModeSettings(identifier, version, migrations.sortedBy { it.toVersion })
    }
}

@PolarPaperSettingsDsl
class HandlersBuilder internal constructor() {
    internal val builtIns = linkedSetOf<BuiltInHandler>()
    internal val custom = mutableListOf<ChunkDataHandler<*>>()

    fun entities() {
        builtIns += BuiltInHandler.ENTITIES
    }

    fun nexoFurniture() {
        builtIns += BuiltInHandler.NEXO_FURNITURE
    }

    fun craftEngine() {
        builtIns += BuiltInHandler.CRAFT_ENGINE
    }

    fun add(handler: ChunkDataHandler<*>) {
        custom += handler
    }
}

@PolarPaperSettingsDsl
class PolarPaperSettingsBuilder internal constructor() {
    private val gameModes = linkedMapOf<String, GameModeSettings>()
    private val builtIns = linkedSetOf<BuiltInHandler>()
    private val handlers = mutableListOf<ChunkDataHandler<*>>()
    private val dataTypes = mutableListOf<DataType<*>>()
    private var autosaveInterval: Duration? = null
    private val loadHooks = mutableListOf<(SurfChunk) -> Unit>()
    private val saveHooks = mutableListOf<(SurfChunk) -> Unit>()

    fun gameMode(identifier: String, block: GameModeSettingsBuilder.() -> Unit) {
        GameModeIdentifiers.requireValid(identifier)
        require(identifier !in gameModes) { "Game mode '$identifier' is configured twice" }
        gameModes[identifier] = GameModeSettingsBuilder(identifier).apply(block).build()
    }

    fun handlers(block: HandlersBuilder.() -> Unit) {
        val builder = HandlersBuilder().apply(block)
        builtIns += builder.builtIns
        handlers += builder.custom
    }

    fun data(type: DataType<*>) {
        dataTypes += type
    }

    fun autosave(interval: Duration) {
        require(interval.isPositive()) { "Autosave interval must be positive, was $interval" }
        autosaveInterval = interval
    }

    fun onChunkLoad(hook: (SurfChunk) -> Unit) {
        loadHooks += hook
    }

    /**
     * Registers a hook that runs before the data of a chunk is captured for a save. It also runs when a chunk unloads
     * while its world stays loaded, and then the chunk's entities are already gone from the chunk: use the data
     * sections for state that has to survive, not [SurfChunk.bukkitChunk]'s entities.
     */
    fun onChunkSave(hook: (SurfChunk) -> Unit) {
        saveHooks += hook
    }

    internal fun build(): PolarPaperSettings {
        validateKeys()

        return PolarPaperSettings(
            gameModes.toMap(),
            builtIns.toSet(),
            handlers.toList(),
            dataTypes.toList(),
            autosaveInterval,
            loadHooks.toList(),
            saveHooks.toList()
        )
    }

    private fun validateKeys() {
        val custom = dataTypes.map { it.key } + handlers.map { it.type.key }
        custom.firstOrNull { it in BuiltInData.RESERVED_KEYS }?.let {
            throw IllegalArgumentException("Data key '${it.asString()}' is reserved for a built-in handler")
        }

        // Built-in keys of enabled handlers count too, so every key is owned by exactly one type.
        val all = custom + builtIns.flatMap { builtInKeys(it) }
        val seen = hashSetOf<Key>()
        all.firstOrNull { !seen.add(it) }?.let {
            throw IllegalArgumentException("Data key '${it.asString()}' is used more than once")
        }
    }

    private fun builtInKeys(handler: BuiltInHandler): List<Key> = when (handler) {
        BuiltInHandler.ENTITIES -> listOf(BuiltInData.ENTITIES.key, BuiltInData.CHUNK_PDC.key)
        BuiltInHandler.NEXO_FURNITURE -> listOf(BuiltInData.NEXO_FURNITURE.key)
        BuiltInHandler.CRAFT_ENGINE ->
            listOf(BuiltInData.CRAFT_ENGINE_FURNITURE.key, BuiltInData.CRAFT_ENGINE_BLOCKS.key)
    }
}

/** Builds and validates settings. Throws [IllegalArgumentException] naming the offending game mode or key. */
fun polarPaperSettings(block: PolarPaperSettingsBuilder.() -> Unit): PolarPaperSettings =
    PolarPaperSettingsBuilder().apply(block).build()
