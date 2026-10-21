# SurfWorld / SurfChunk, Chunk Data Handlers and World Migrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A per-plugin API instance that loads Polar worlds through typed per-chunk and per-world data sections, built-in handlers (entities, Nexo, CraftEngine), plugin-defined handlers, and integer-versioned world migrations with raw and live stages.

**Architecture:**
- **API (`surf-polar-paper-api`).** It becomes Paper-aware and defines the contracts: settings DSL, `DataType`, `ChunkDataHandler`, `RawWorld`/`RawChunk`, `WorldMigration`, `SurfWorld`/`SurfChunk`.
- **Core (`core-client`).** It holds the platform-neutral engine: storage envelope v2 with the world version, the section container codec, migration planning, game mode ownership, and the per-plugin API implementation.
- **Paper runtime (`runtime-paper`).** It holds everything that touches Polar and NMS:
  - the raw Polar adapter
  - our `PolarWorldAccess`
  - the load/save pipeline with force-loading for live stages
  - the built-in handlers

**Tech Stack:** Kotlin, polarpaper 2.3.1, Paper 26.3 (unobfuscated, Mojang names), kotlinx-serialization CBOR 1.11, mccoroutine-folia, JUnit 5 + MockK + `runTest`, Nexo 1.29.1, CraftEngine 26.9.1.

**Spec:** `docs/superpowers/specs/2026-10-21-surf-world-chunks-and-migrations-design.md`. Read it fully before any task. The section "Settled after the builder review" is binding.

## Global Constraints

- **Compatibility.** No backwards compatibility is needed: not with the old envelope, the old API or the old commands. The database schema stays unchanged.
- **World versions:**
  - A world version is an `Int`. Migration N upgrades N-1 to N.
  - Stored > current → refuse.
  - A missing step → skip, and the version still moves up.
  - Any failure → abort, and storage is untouched.
- **Stage order.** All raw stages run in version order, then the Bukkit world is created once, then (only if any pending migration has a live stage) every chunk is force-loaded and all live stages run in version order.
- **Unhandled sections:**
  - Any section key with no registered `DataType` or no usable handler aborts the load.
  - The check runs after the raw stages, or after the live stages when live stages exist.
  - On failure the Bukkit world is unloaded without saving.
- **Neutral formats.** Stored section bytes are kotlinx CBOR of the `DataType`'s serializer. Nothing stored may need Nexo or CraftEngine classes to decode.
- **Section keys:**

  | Key | Contents |
  |---|---|
  | `surf:entities` | all entities except players |
  | `surf:chunk-pdc` | the chunk's PDC |
  | `nexo:furniture` | Nexo furniture records |
  | `craftengine:furniture` | CraftEngine furniture records |
  | `craftengine:blocks` | CraftEngine custom block records |

- **World creation.** A new world is stamped with the game mode's current version unless `createWorld(..., worldVersion = n)` is given. After a successful migration (or any version change), the world is saved immediately, before it is returned.
- **Game mode ownership.** One owning plugin per game mode, process-wide. A second claim throws `GameModeAlreadyOwnedException`.
- **Handlers.** Handlers and data types are per API instance. Built-ins are opt-in.
- **Code conventions:**
  - Follow the existing style: short KDoc on interfaces and non-obvious behaviour, `//` comments that state why, flogger via `logger()`, and backtick test names.
  - Commits use Conventional Commits in English with scopes `api`, `client`, `paper`, `build`, and end with the line `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- **Build state between tasks.** Between Task 5 and Task 8, `runtime-paper` does not compile. Verify those tasks per module. From Task 8 on, `./gradlew build` must pass.

## Review Focus

1. **A world whose stored version is greater than the registered version must not be touched.** No platform world is created and nothing is written to storage. (Owned by Task 5.)
2. **A migration that throws halfway through the chain must leave storage byte-identical.** The lock is released and the world is not in `loadedWorlds`. (Task 5 for core, Task 8 for the platform world being unloaded.)
3. **Furniture must not be stored twice.** An entity claimed by a handler during capture must not appear in `surf:entities`. (Task 7.)
4. **A `nexo:furniture` section on a server without Nexo, and with no migration converting it, must abort the load.** It must be readable by a live stage and must not abort once that stage removes it. (Task 8.)
5. **Template bytes in Polar's own `EntitiesWorldAccess` chunk format must import into `surf:entities` / `surf:chunk-pdc`.** They must not be treated as an unknown section, and empty `userData` must import as no sections. (Task 6.)

---

### Task 1: Build setup for Paper types, NMS and the furniture plugins

**Files:**
- Modify: `surf-polar-paper-api/build.gradle.kts`
- Modify: `surf-polar-paper-core/surf-polar-paper-core-client/build.gradle.kts`
- Modify: `surf-polar-paper-runtime/surf-polar-paper-runtime-paper/build.gradle.kts`
- Test: `surf-polar-paper-runtime/surf-polar-paper-runtime-paper/src/test/kotlin/dev/slne/surf/polarpaper/runtime/paper/NmsClasspathTest.kt`

**Interfaces:**
- Produces:
  - `paper-api` 26.3 on the compile classpath of `api` (exported to consumers as compileOnly) and of `core-client`.
  - NMS/Paper server classes (`net.minecraft.*`, `ca.spottedleaf.moonrise.*`) on the main and test compile and test runtime classpaths of `runtime-paper`.
  - `com.nexomc:nexo:1.29.1` and `net.momirealms:craft-engine-bukkit:26.9.1` + `craft-engine-core:26.9.1` as compileOnly in `runtime-paper`.
  - Soft server dependencies `Nexo` and `CraftEngine`.

- [ ] **Step 1: Write the test.** `` `nms compound tags are on the test classpath` `` creates `net.minecraft.nbt.CompoundTag()`, calls `putString("id", "minecraft:pig")` on it, and asserts that `getStringOr("id", "")` (or the 26.3 equivalent getter) returns `"minecraft:pig"`.
- [ ] **Step 2: Run** `./gradlew :surf-polar-paper-runtime:surf-polar-paper-runtime-paper:test --tests "*NmsClasspathTest*"`. Expected: a compile FAIL (unresolved `net.minecraft`).
- [ ] **Step 3: Add the dependencies.**
  - First look for an NMS or dev-bundle option in the surf-api gradle plugin 2.2.1. Inspect the plugin jar in the Gradle cache for extension methods on `surfPaperPluginApi`.
  - If there is none, apply `io.papermc.paperweight.userdev` with `paperweight.paperDevBundle("26.3.build.211-stable")`. The dev bundle and paperweight are already in the local Gradle cache. Paper 26.x is unobfuscated, so no reobf/remapping task may be wired into the jar.
  - Add the Nexo and CraftEngine repositories: `https://repo.nexomc.com/releases` and `https://repo.momirealms.net/releases`.
  - For `api` and `core-client`, add `compileOnlyApi`/`compileOnly` `io.papermc.paper:paper-api:26.3.build.+` with the papermc repository, unless the surf plugin offers a Paper API toggle for core modules.
- [ ] **Step 4: Verify.** Run the test from Step 2 (expected: PASS), then `./gradlew build` (expected: BUILD SUCCESSFUL, all existing tests pass).
- [ ] **Step 5: Commit.** `build: add paper, nms and furniture plugin apis to the classpath`

---

### Task 2: API contracts for data, migrations, settings and exceptions

**Files (all under `surf-polar-paper-api/src/main/kotlin/dev/slne/surf/polarpaper/api/`):**
- Create: `data/DataType.kt`, `data/DataSections.kt`, `data/ChunkDataHandler.kt`, `data/BuiltInData.kt` (also holds `StoredEntity`, `FurnitureRecord`, `CustomBlockRecord`)
- Create: `migration/WorldMigration.kt`, `migration/RawWorld.kt` (also holds `RawChunk`, `RawBlockEntity`)
- Create: `settings/PolarPaperSettings.kt` (settings + DSL builder), `settings/BuiltInHandler.kt`
- Create: `world/SurfChunk.kt`, `world/WorldExceptions.kt`
- Test: `surf-polar-paper-api/src/test/kotlin/dev/slne/surf/polarpaper/api/settings/PolarPaperSettingsTest.kt`, `.../data/BuiltInDataTest.kt`

**Interfaces:**
- Consumes: Task 1 (Bukkit types).
- Produces. Every later task uses these exact names:

```kotlin
// data
open class DataType<T : Any>(val key: Key, val serializer: KSerializer<T>)    // equals/hashCode by key only
interface DataSections {
    val keys: Set<Key>
    operator fun <T : Any> get(type: DataType<T>): T?
    operator fun <T : Any> set(type: DataType<T>, value: T?)    // null removes the section
    fun remove(key: Key)
    fun contains(key: Key): Boolean
}
interface ChunkDataHandler<T : Any> {
    val type: DataType<T>
    fun isAvailable(): Boolean = true
    // Runs on the chunk's region thread while saving. Entities passed to claim() are not stored by the entities handler.
    fun capture(chunk: Chunk, entities: List<Entity>, claim: (Entity) -> Unit): T?
    // Runs on the chunk's region thread once the chunk is live.
    fun restore(chunk: Chunk, value: T)
}
@Serializable data class StoredEntity(val type: String, val x: Double, val y: Double, val z: Double,
    val yaw: Float, val pitch: Float, @ByteString val nbt: ByteArray)    // world coordinates; nbt = Polar EntitySerializer bytes
@Serializable data class FurnitureRecord(val id: String, val x: Double, val y: Double, val z: Double,
    val yaw: Float, val pitch: Float, val properties: Map<String, String> = emptyMap())
@Serializable data class CustomBlockRecord(val id: String, val x: Int, val y: Int, val z: Int,
    val properties: Map<String, String> = emptyMap())
object BuiltInData {
    val ENTITIES: DataType<List<StoredEntity>>                   // surf:entities
    val CHUNK_PDC: DataType<ByteArray>                           // surf:chunk-pdc
    val NEXO_FURNITURE: DataType<List<FurnitureRecord>>          // nexo:furniture
    val CRAFT_ENGINE_FURNITURE: DataType<List<FurnitureRecord>>  // craftengine:furniture
    val CRAFT_ENGINE_BLOCKS: DataType<List<CustomBlockRecord>>   // craftengine:blocks
}

// migration
interface RawWorld {
    val minSection: Int; val maxSection: Int
    val chunks: Collection<RawChunk>
    fun chunk(x: Int, z: Int): RawChunk?
    fun removeChunk(x: Int, z: Int)
    val data: DataSections                                       // world-level sections
}
interface RawChunk {
    val x: Int; val z: Int
    fun getBlock(x: Int, y: Int, z: Int): String                 // local x/z 0..15, world y; state string, e.g. "minecraft:oak_log[axis=y]"
    fun setBlock(x: Int, y: Int, z: Int, state: String)
    val blockEntities: MutableList<RawBlockEntity>
    val data: DataSections
}
data class RawBlockEntity(val x: Int, val y: Int, val z: Int, val id: String?, val snbt: String?)   // local x/z, world y
abstract class WorldMigration(val toVersion: Int) {
    open fun migrateRaw(world: RawWorld) {}
    open val hasLiveStage: Boolean get() = false
    // Runs for every chunk after all raw stages, on that chunk's region thread.
    open fun migrateLive(chunk: SurfChunk) {}
}

// settings
enum class BuiltInHandler { ENTITIES, NEXO_FURNITURE, CRAFT_ENGINE }
class GameModeSettings(val identifier: String, val currentVersion: Int, val migrations: List<WorldMigration>)
class PolarPaperSettings(
    val gameModes: Map<String, GameModeSettings>,
    val builtInHandlers: Set<BuiltInHandler>,
    val handlers: List<ChunkDataHandler<*>>,
    val dataTypes: List<DataType<*>>,                            // plain sections set by gameplay code, no capture
    val autosaveInterval: Duration?,                             // null = no autosave (default)
    val chunkLoadHooks: List<(SurfChunk) -> Unit>,
    val chunkSaveHooks: List<(SurfChunk) -> Unit>,
)
fun polarPaperSettings(block: PolarPaperSettingsBuilder.() -> Unit): PolarPaperSettings
// DSL: gameMode("skyblock") { version = 3; migration(m) }, handlers { entities(); nexoFurniture(); craftEngine(); add(h) },
//      data(type), autosave(5.minutes), onChunkLoad { }, onChunkSave { }

// world
interface SurfChunk { val world: SurfWorld; val x: Int; val z: Int; val bukkitChunk: Chunk; val data: DataSections }
class GameModeAlreadyOwnedException(val gameModeIdentifier: String, val owner: String) : IllegalStateException
class WorldVersionTooNewException(val worldUuid: UUID, val gameModeIdentifier: String, val storedVersion: Int, val currentVersion: Int) : IllegalStateException
class WorldMigrationException(val worldUuid: UUID, val gameModeIdentifier: String, val toVersion: Int, cause: Throwable) : IllegalStateException
class UnhandledWorldDataException(val worldUuid: UUID, val gameModeIdentifier: String, val keys: Set<Key>) : IllegalStateException
```
`SurfChunk` refers to `SurfWorld`, which Task 5 turns into an interface. Until then it refers to the existing class. That is fine.

- [ ] **Step 1: Write the failing tests.**
  - `PolarPaperSettingsTest`:
    - `` `builds game modes with version and migrations sorted by toVersion` ``: adding migrations for versions 3 and 2 gives `migrations.map { it.toVersion } == listOf(2, 3)`.
    - `` `rejects a migration above the current version` ``: version 2 with a migration to 3 → `IllegalArgumentException`.
    - `` `rejects two migrations to the same version` ``
    - `` `rejects a negative version` ``
    - `` `rejects an invalid game mode identifier` ``: uses `GameModeIdentifiers`.
    - `` `rejects the same game mode twice` ``
    - `` `rejects two data types or handlers with the same key` ``: this includes a custom type that reuses a built-in key such as `surf:entities`.
    - `` `autosave is off unless configured` ``
  - `BuiltInDataTest` `` `built-in keys match the spec` ``: asserts the five keys as `Key.key("surf","entities")` and so on.
- [ ] **Step 2: Run** `./gradlew :surf-polar-paper-api:test`. Expected: compile FAIL.
- [ ] **Step 3: Implement the contracts above.** Validate in `polarPaperSettings` and throw `IllegalArgumentException` with a message that names the offending game mode or key.
- [ ] **Step 4: Run** `./gradlew :surf-polar-paper-api:test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(api): add data sections, handlers, migrations and settings contracts`

---

### Task 3: Storage envelope v2 and the section container codec

**Files:**
- Modify: `surf-polar-paper-core/surf-polar-paper-core-client/src/main/kotlin/dev/slne/surf/polarpaper/core/client/utils/WorldStorageCodec.kt`
- Create: `.../core/client/data/SectionContainerCodec.kt`, `.../core/client/data/MapDataSections.kt`
- Test: `.../test/.../utils/WorldStorageCodecTest.kt` (rewrite), `.../test/.../data/SectionContainerCodecTest.kt`, `.../test/.../data/MapDataSectionsTest.kt`

**Interfaces:**
- Consumes: `DataType`, `DataSections` (Task 2).
- Produces:

```kotlin
object WorldStorageCodec {    // FORMAT_VERSION = 2
    fun encode(worldVersion: Int, worldBytes: ByteArray): ByteArray
    fun decode(bytes: ByteArray): DecodedWorld    // rejects formatVersion != 2
}
data class DecodedWorld(val worldVersion: Int, val worldBytes: ByteArray)
object SectionContainerCodec {
    fun isContainer(bytes: ByteArray?): Boolean                  // starts with MAGIC
    fun encode(sections: Map<String, ByteArray>): ByteArray      // MAGIC + CBOR(formatVersion = 1, sections)
    fun decode(bytes: ByteArray?): Map<String, ByteArray>        // null/empty → empty map; no MAGIC → IllegalArgumentException
}
class MapDataSections(initial: Map<String, ByteArray> = emptyMap()) : DataSections {
    fun raw(): Map<String, ByteArray>                            // snapshot for encoding
    fun <T : Any> read(key: Key, serializer: KSerializer<T>): T? // decode without a DataType
}
```
`MAGIC` is the 4 bytes `0x53 0x55 0x52 0x46` ("SURF"). Values are CBOR from `CborUtils.cbor`. Writes are thread-safe, using a `ConcurrentHashMap`, because hooks and save can run on different region threads.

- [ ] **Step 1: Write the failing tests.**
  - `` `encodes and decodes the world version with the bytes` ``
  - `` `rejects format version 1` ``: hand-encode a v1 envelope.
  - `` `container round-trips sections` ``
  - `` `empty or null user data decodes to no sections` ``
  - `` `bytes without the magic are not a container` ``
  - `` `typed get returns what set stored` ``: uses a `DataType<List<FurnitureRecord>>`.
  - `` `set null removes the section` ``
  - `` `read decodes a section by key and serializer without a registered type` ``
- [ ] **Step 2: Run** `./gradlew :surf-polar-paper-core:surf-polar-paper-core-client:test --tests "*Codec*" --tests "*MapDataSections*"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Update all callers of `WorldStorageCodec` to the new signature. Until Task 5 rewrites `SurfPolarPaperApiImpl`, it passes `0` as the version and reads `.worldBytes`.
  - Update the existing storage provider tests that encode/decode.
- [ ] **Step 4: Run** `./gradlew :surf-polar-paper-core:surf-polar-paper-core-client:test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(client): store the world version and add the section container codec`

---

### Task 4: Migration planning and game mode ownership

**Files:**
- Create: `.../core/client/migration/MigrationPlanner.kt`, `.../core/client/ownership/GameModeOwnership.kt`
- Test: `.../test/.../migration/MigrationPlannerTest.kt`, `.../test/.../ownership/GameModeOwnershipTest.kt`

**Interfaces:**
- Consumes: `GameModeSettings`, `WorldMigration`, the exceptions (Task 2).
- Produces:

```kotlin
object MigrationPlanner {
    // Migrations with storedVersion < toVersion <= currentVersion, ascending.
    // Empty when equal, or when the steps in between have no migration.
    // Throws WorldVersionTooNewException when storedVersion > currentVersion.
    fun plan(worldUuid: UUID, gameMode: GameModeSettings, storedVersion: Int): List<WorldMigration>
}
class GameModeOwnership {    // one process-wide instance lives in ApiInstances (Task 5)
    fun claim(gameModeIdentifier: String, owner: String)    // GameModeAlreadyOwnedException if held by another owner
    fun release(owner: String)
    fun ownerOf(gameModeIdentifier: String): String?
}
```
- [ ] **Step 1: Write the failing tests.**
  - `` `equal versions plan nothing` ``
  - `` `plans migrations above the stored version in order` ``: stored 1, current 4, migrations 2 and 4 → `[2, 4]`.
  - `` `gaps are skipped` ``: stored 1, current 3, no migrations → empty.
  - `` `a world at version 1 plans every migration up to 100 in order` ``:
    - Migrations are registered in shuffled order: every version from 2 to 100 except 50–59.
    - Plan from 1 → exactly `(2..100) - (50..59)`, ascending.
    - Plan from 55 → `60..100`.
    - Plan from 100 → empty.
  - `` `stored newer than current is refused` ``: asserts the exception fields 5 and 4.
  - `` `a second owner cannot claim a game mode` ``
  - `` `release frees all game modes of an owner` ``
  - `` `the same owner may claim twice` ``
- [ ] **Step 2: Run** `./gradlew :surf-polar-paper-core:surf-polar-paper-core-client:test --tests "*MigrationPlanner*" --tests "*GameModeOwnership*"`. Expected: FAIL.
- [ ] **Step 3: Implement.** Use a `ConcurrentHashMap` with `compute` for claims.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(client): plan world migrations and track game mode owners`

---

### Task 5: Per-plugin API instance, SurfWorld interface and the new bridge contract

**Files:**
- Modify (rewrite): `surf-polar-paper-api/.../api/SurfPolarPaperApi.kt`, `surf-polar-paper-api/.../api/world/SurfWorld.kt` (it becomes an interface)
- Create: `surf-polar-paper-api/.../api/SurfPolarPaperApiFactory.kt`
- Move + rewrite: `core-common/.../world/PlatformWorldBridge.kt` → `core-client/.../core/client/world/PlatformWorldBridge.kt`. Delete `core-common/.../world/WorldCacheKey.kt`'s `SurfWorld.key()` if it no longer compiles, and move `WorldCacheKey` to core-client if the microservice does not use it. Check with grep.
- Create: `.../core/client/world/SurfWorldImpl.kt`, `.../core/client/ApiInstances.kt`, `.../core/client/SurfPolarPaperApiFactoryImpl.kt`
- Modify (rewrite): `.../core/client/SurfPolarPaperApiImpl.kt`, `.../core/client/SurfPolarPaperInstance.kt` (onDisable closes all instances)
- Modify: `surf-polar-paper-api/src/test/.../world/SurfWorldTest.kt` (delete or adapt), the fakes in `core-client/src/test/.../fakes/`, `SurfPolarPaperApiImplTest.kt`, `SurfPolarPaperApiImplLockTest.kt`, `SurfPolarPaperInstanceTest.kt`

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces:

```kotlin
// api
interface SurfPolarPaperApi {
    val plugin: Plugin
    val settings: PolarPaperSettings
    val loadedWorlds: @UnmodifiableView ObjectSet<SurfWorld>    // worlds of this instance only
    suspend fun listWorlds(gameModeIdentifier: String, showLoadedOnly: Boolean = false): List<WorldInfo>
    suspend fun createWorld(worldUuid: UUID, gameModeIdentifier: String, polarBytes: ByteArray? = null, worldVersion: Int? = null): SurfWorld
    suspend fun loadWorld(worldUuid: UUID, gameModeIdentifier: String): SurfWorld
    suspend fun saveWorld(world: SurfWorld)
    suspend fun saveAllWorlds()
    suspend fun unloadWorld(world: SurfWorld, saveChanges: Boolean = true)
    suspend fun unloadAllWorlds(saveChanges: Boolean = true)
    suspend fun deleteWorld(world: SurfWorld)
    fun findWorld(worldUuid: UUID, gameModeIdentifier: String): SurfWorld?
    suspend fun close()    // save + unload own worlds, stop autosave, release game modes; idempotent
    companion object {
        fun create(plugin: Plugin, settings: PolarPaperSettingsBuilder.() -> Unit): SurfPolarPaperApi
    }
}
interface SurfPolarPaperApiFactory { fun create(plugin: Plugin, settings: PolarPaperSettings): SurfPolarPaperApi }    // service-loaded
interface SurfWorld : ComponentLike {
    val uuid: UUID; val gameModeIdentifier: String; val name: String; val version: Int
    val api: SurfPolarPaperApi
    val bukkitWorld: World                // throws if not loaded
    val data: DataSections                // world-level sections
    fun chunk(x: Int, z: Int): SurfChunk? // only live chunks
    suspend fun save(); suspend fun unload(saveChanges: Boolean = true); suspend fun delete()
    fun info(): WorldInfo
}

// core-client
interface PlatformWorldBridge {    // service-loaded, implemented in runtime-paper
    // polarBytes null = new empty world.
    // Order: raw stages → section check (when no live stages) → platform world → force-load + live stages → section check.
    // Fills world.data from the Polar world user data.
    // On failure the platform world is unloaded without saving, and the exception propagates.
    suspend fun loadWorld(world: SurfWorldImpl, polarBytes: ByteArray?, migrations: List<WorldMigration>)
    suspend fun saveWorld(world: SurfWorldImpl): ByteArray    // Polar bytes incl. sections and world.data
    suspend fun unloadWorld(world: SurfWorldImpl)              // without saving; no-op if not loaded
    fun bukkitWorld(world: SurfWorldImpl): World?
    fun chunk(world: SurfWorldImpl, x: Int, z: Int): SurfChunk?
}
class SurfWorldImpl(uuid, gameModeIdentifier, version: Int /*var, internal set*/, api: SurfPolarPaperApiImpl, bridge) : SurfWorld {
    val data: MapDataSections
}
object ApiInstances {
    val ownership: GameModeOwnership
    fun register(api: SurfPolarPaperApiImpl); fun unregister(api: SurfPolarPaperApiImpl)
    fun ownerOf(gameModeIdentifier: String): SurfPolarPaperApiImpl?
    fun forPlugin(plugin: Plugin): List<SurfPolarPaperApiImpl>
    val all: List<SurfPolarPaperApiImpl>
}
```
**Load flow in `SurfPolarPaperApiImpl.loadWorld`:**
1. Return the cached world if there is one.
2. `requireOwned(gameMode)`, which throws `IllegalArgumentException` when the game mode is not in this instance's settings.
3. Lock the world.
4. Decode the stored envelope.
5. `MigrationPlanner.plan`.
6. `bridge.loadWorld(world /*version = stored*/, bytes, plan)`.
7. Set `world.version = currentVersion`.
8. If the version changed, save immediately.
9. Cache the world.

On any exception: `bridge.unloadWorld` (best effort), release the lock, do not cache, rethrow. Wrap migration failures that are not already `WorldMigrationException`/`UnhandledWorldDataException`/`WorldVersionTooNewException` as today ("Failed to load platform world").

**Create flow:**
1. Version is `worldVersion ?: current`.
2. Plan from that version.
3. Run the bridge load with `polarBytes`.
4. Set the current version.
5. Save.

**Autosave.** When `autosaveInterval != null`, a coroutine on a `SupervisorJob` scope calls `saveAllWorlds()` every interval and logs failures. `close()` cancels it.

The constructor keeps the injectable suppliers pattern for tests: `SurfPolarPaperApiImpl(plugin, settings, storageSupplier, bridgeSupplier, locksSupplier)`. The factory claims the game modes, using `plugin.name` as the owner, before it registers the instance. If the claim fails, nothing is registered.

- [ ] **Step 1: Write the failing tests.** Use fakes: `InMemoryStorageProvider`; `FakePlatformWorldBridge`, rewritten to record `"load:<uuid>:<migrations toVersions>"` and to optionally throw; `FakeWorldLockService`; and a MockK `Plugin` with `name = "TestPlugin"`. Tests in `SurfPolarPaperApiImplTest`:
  - `` `loading a world stored at the current version runs no migrations and does not save` ``
  - `` `loading an older world passes the planned migrations and saves at the current version` ``: the stored envelope's `worldVersion` becomes the current version.
  - `` `loading a newer world throws and leaves storage untouched and the lock released` ``
  - `` `a failing bridge load leaves storage byte-identical, releases the lock and is not cached` ``
  - `` `create stamps the current version unless a template version is given` ``: with `worldVersion = 1` and current 3, the bridge gets migrations > 1.
  - `` `an instance cannot load a game mode it does not own` ``
  - `` `creating a second instance for an owned game mode throws and registers nothing` ``
  - `` `close saves and unloads own worlds and frees the game modes` ``
  - `` `autosave saves loaded worlds every interval` ``: uses `runTest` virtual time and `advanceTimeBy`.
  - Keep the existing lock tests passing: adapt `SurfPolarPaperApiImplLockTest`.
- [ ] **Step 2: Run** `./gradlew :surf-polar-paper-core:surf-polar-paper-core-client:test`. Expected: FAIL.
- [ ] **Step 3: Implement the interfaces and flows above.** `SurfPolarPaperInstance.onDisable` calls `close()` on every instance in `ApiInstances.all`, not `api.saveAllWorlds()`. It collects failures as `forEachLoadedWorld` does today, then disconnects.
- [ ] **Step 4: Run** `./gradlew :surf-polar-paper-api:test :surf-polar-paper-core:surf-polar-paper-core-client:test :surf-polar-paper-runtime:surf-polar-paper-runtime-microservice:compileKotlin`. Expected: PASS. `runtime-paper` is allowed to fail compiling until Task 8.
- [ ] **Step 5: Commit.** `feat(client)!: create one api instance per plugin with versioned world loading`

---

### Task 6: Raw Polar adapter and the import of Polar's legacy user data

**Files (under `surf-polar-paper-runtime/surf-polar-paper-runtime-paper/src/main/kotlin/dev/slne/surf/polarpaper/runtime/paper/`):**
- Create: `raw/PolarRawWorld.kt`, `raw/PolarRawChunk.kt`, `raw/LegacyUserDataImporter.kt`
- Test: `src/test/.../raw/PolarRawChunkTest.kt`, `src/test/.../raw/LegacyUserDataImporterTest.kt`

**Interfaces:**
- Consumes: `RawWorld`, `RawChunk`, `RawBlockEntity`, `BuiltInData` (Task 2), `SectionContainerCodec`, `MapDataSections` (Task 3).
- Produces:

```kotlin
class PolarRawWorld(polarWorld: PolarWorld) : RawWorld {
    // Writes every touched chunk's sections back into PolarChunk.userData (SectionContainerCodec)
    // and world.data into PolarWorld.userData, using updateChunkAt / withUserData.
    fun flush()
}
class PolarRawChunk : RawChunk   // block get/set over PolarSection palettes; block entities via SNBT ⇄ CompoundTag
object LegacyUserDataImporter {
    // Chunk userData that is not a container: parse Polar's EntitiesWorldAccess format into surf:entities and surf:chunk-pdc.
    // Empty or null → no sections. World userData that is not a container → no sections.
    fun importChunk(userData: ByteArray?, chunkX: Int, chunkZ: Int): Map<String, ByteArray>
    fun normalize(polarWorld: PolarWorld)    // rewrites every chunk's userData into container form in place
}
```
To learn the exact byte layout `EntitiesWorldAccess` writes, read it with `javap -c -p` on `live.minehub.polarpaper.util.EntitiesWorldAccess`, `core.userdata.EntityUtil` and `core.world.PolarEntity` from the polarpaper 2.3.1 jar in the Gradle cache. `StoredEntity.type` comes from the NBT `id`.

Setting a block must:
- add the state to the section palette when it is missing
- repack `blockData` when the bits per entry grow
- handle a section whose palette is a single state with empty data

Use Polar's `PaletteUtil` where it fits.

- [ ] **Step 1: Write the failing tests.** Build `PolarWorld`/`PolarChunk`/`PolarSection` instances directly. If the light arrays (`SWMRNibbleArray`) cannot be built in a unit test, use the `PolarSection(LightContent, ...)` constructor with `LightContent.MISSING` and nulls.
  - `` `getBlock reads the state from a single-state section` ``
  - `` `setBlock grows the palette and keeps other blocks` ``: set 3 different states, then read all 4096 positions back.
  - `` `block entities round-trip through snbt` ``
  - `` `flush writes sections as a container into chunk and world user data` ``
  - `` `legacy entities user data imports into surf entities and chunk pdc` ``: build the bytes with `EntityUtil.writeEntities` plus the PDC layout learned from the bytecode.
  - `` `empty legacy user data imports as no sections` ``
  - `` `container user data is left untouched by normalize` ``
- [ ] **Step 2: Run** `./gradlew :surf-polar-paper-runtime:surf-polar-paper-runtime-paper:test --tests "*raw*"`. Expected: FAIL. The module may not compile because of Task 5. If so, do the minimal `PlatformWorldBridgePaper` signature stubs (`TODO("Task 8")` bodies) so the module compiles, and say so in the commit.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(paper): edit raw polar chunks and import polar entity user data`

---

### Task 7: Our PolarWorldAccess, the entities handler and live SurfChunks

**Files (runtime-paper):**
- Create: `access/SurfWorldAccess.kt`, `access/LiveChunkRegistry.kt`, `world/SurfChunkImpl.kt`, `handler/EntitiesHandler.kt`, `handler/BuiltInHandlers.kt`
- Test: `src/test/.../access/SurfWorldAccessTest.kt`, `src/test/.../handler/EntitiesHandlerTest.kt`

**Interfaces:**
- Consumes: Tasks 2, 3, 5 (`SurfWorldImpl`, `MapDataSections`).
- Produces:

```kotlin
class LiveChunkRegistry {    // per world: chunk key → MapDataSections of live chunks
    fun sections(x: Int, z: Int): MapDataSections?
    fun put(x: Int, z: Int, sections: MapDataSections); fun remove(x: Int, z: Int)
    fun all(): Map<Long, MapDataSections>
}
class SurfChunkImpl(world: SurfWorldImpl, bukkitChunk: Chunk, data: MapDataSections) : SurfChunk
object BuiltInHandlers {    // BuiltInHandler → handlers; NEXO/CRAFT_ENGINE are filled in by Tasks 9/10
    fun resolve(builtIns: Set<BuiltInHandler>, plugin: Plugin): List<ChunkDataHandler<*>>
}
class EntitiesHandler(plugin: Plugin) {    // not a ChunkDataHandler: it runs last and gets the unclaimed entities
    fun capture(chunk: Chunk, unclaimed: List<Entity>, sections: MapDataSections)    // writes ENTITIES and CHUNK_PDC
    fun restore(chunk: Chunk, sections: MapDataSections)
}
class SurfWorldAccess(world: SurfWorldImpl, handlers: List<ChunkDataHandler<*>>, entities: EntitiesHandler?, live: LiveChunkRegistry) : PolarWorldAccess
```
**Behaviour of `SurfWorldAccess`:**
- **`populateChunkData(chunk, bytes)`:**
  1. Decode the container into `MapDataSections`.
  2. Register it in `live`.
  3. For each handler with a section present and `isAvailable()`, call `restore`.
  4. Run the entities restore.
  5. Run `settings.chunkLoadHooks` with the `SurfChunkImpl`.

  A section without a usable handler stays in the map untouched.
- **`saveChunkData(chunkAccess, blockEntities, entities, writer)`:**
  1. Take the live map for the chunk, or decode an empty one.
  2. Run `chunkSaveHooks`.
  3. For each available handler, call `capture(bukkitChunk, nonPlayerEntities, claim)` and set or remove its section.
  4. Call `entities.capture` with the unclaimed entities.
  5. Write `SectionContainerCodec.encode(map.raw())` to `writer`.

  Plain `dataTypes` sections and sections without a handler are written back unchanged.
- **`loadChunkData`:** a no-op. Restoring happens in `populateChunkData` because it needs the live chunk.
- **Entities.** `EntitiesHandler` uses Polar's `EntitySerializer` for the 26.3 Paper version. Find the serializer through `live.minehub.polarpaper.paper_26_3.EntitySerializerImpl` or the way `EntitiesWorldAccess` obtains it. To restore, it uses `compoundToEntity` / `EntityUtil.spawnEntity` at the stored position.

- [ ] **Step 1: Write the failing tests.** Use MockK for `Chunk`, `Entity`, `World` and the handlers. Mock `EntitySerializer` statics where needed.
  - `` `claimed entities are not stored as generic entities` ``
  - `` `sections without a handler are written back unchanged on save` ``
  - `` `unavailable handlers do not restore and keep their section` ``
  - `` `handlers restore their section when the chunk becomes live` ``
  - `` `chunk save hooks run before capture` ``
  - `` `a handler returning null removes its section` ``
  - `` `players are never passed to handlers or stored` ``
- [ ] **Step 2: Run** `./gradlew :surf-polar-paper-runtime:surf-polar-paper-runtime-paper:test --tests "*access*" --tests "*handler*"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(paper): persist entities and handler sections per chunk`

---

### Task 8: Paper load/save pipeline, plugin wiring, commands and README

**Files (runtime-paper):**
- Rewrite: `PlatformWorldBridgePaper.kt`, `PaperMain.kt` (`PluginDisableEvent` → close that plugin's instances), `SurfPolarPaperInstancePaper.kt` if needed, `commands/*.kt`
- Modify: `core-client/.../config/PolarPaperConfig.kt` (remove `gameModeIdentifier`), `README.md`
- Test: `src/test/.../PlatformWorldBridgePaperTest.kt` (rewrite), `src/test/.../commands/WorldCommandTest.kt` (adapt)

**Interfaces:**
- Consumes: everything above.
- Produces: a working `PlatformWorldBridgePaper : PlatformWorldBridge`.

**`loadWorld`:**
1. Read `polarBytes` with `PolarReader().read(BytesPolarSource(bytes))`, or use an empty `PolarWorld(-4, 19)`.
2. `LegacyUserDataImporter.normalize`.
3. Wrap the world in `PolarRawWorld` and run `migrateRaw` for each migration, in order, then `flush()`. Wrap failures in `WorldMigrationException(toVersion)`.
4. Copy the world user data into `world.data`.
5. If no migration `hasLiveStage`, run the **section check** on the raw world.
6. `Polar.createWorld(polarWorld, world.name, SurfWorldAccess(...))`.
7. If any migration has a live stage:
   - Force-load every chunk in `polarWorld.chunks()` with `World.getChunkAtAsync(x, z)`, at most 32 concurrent. Add a plugin chunk ticket while migrating, then remove it.
   - On each chunk's region thread, run each live migration's `migrateLive(SurfChunkImpl)` in order.
   - Run the section check over `live.all()` plus the chunks not in Polar's chunk list.
8. On any failure, unload the platform world without saving and rethrow.

**The section check.** The known keys are the registered data types, plus the keys of handlers whose `isAvailable()` is true, plus the `ENTITIES`/`CHUNK_PDC` keys when the entities built-in is on. Any other key in any chunk or in the world data throws `UnhandledWorldDataException` with all offending keys.

**`saveWorld`:** `Polar.saveWorld(bukkitWorld, polarWorld.chunks(), BytesPolarSource(), access, BlockSelector.ALL, config)`, or whichever overload uses our access. It writes `world.data` into the Polar world user data and returns the bytes.

**Commands:**
- `list` and `join` read across `ApiInstances.all`. For `list`, use the storage of the owning instance, or any instance, for unowned game modes.
- `create`, `load`, `unload` and `delete` look up `ApiInstances.ownerOf(gameMode)`. If there is none, they fail with the message `No plugin owns game mode '<id>'`.

**README:** update the API usage to show `SurfPolarPaperApi.create(this) { ... }`, a migration example (the strawberries case), a handler example, the built-in handlers, the version rules, and remove `game-mode-identifier`.

- [ ] **Step 1: Write the failing tests.** Use MockK for `Polar`/`Bukkit` statics, as the existing test does.
  - `` `raw stages run in version order before the platform world is created` ``
  - `` `a world migrated from 1 to 100 applies all 99 raw stages in order on the same data` ``:
    - Each migration N appends N to a world-level `DataType<List<Int>>` section.
    - Afterwards the section equals `(2..100).toList()`.
    - Migration 100 can see what migration 2 wrote.
  - `` `no force load happens without live stages` ``
  - `` `live stages run for every chunk after all raw stages` ``
  - `` `a section without handler aborts the load and unloads the platform world` ``
  - `` `a nexo section removed by a live stage does not abort the load` ``
  - `` `a throwing raw stage is wrapped with its version and no platform world is created` ``
  - `` `save returns polar bytes including world data` ``
  - `` `commands refuse game modes without an owner` ``
- [ ] **Step 2: Run** `./gradlew :surf-polar-paper-runtime:surf-polar-paper-runtime-paper:test`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew build`. Expected: BUILD SUCCESSFUL, all modules and tests.
- [ ] **Step 5: Commit.** `feat(paper): migrate worlds on load and save them with chunk data sections`

---

### Task 9: Built-in Nexo furniture handler

**Files:**
- Create: `runtime-paper/.../handler/NexoFurnitureHandler.kt`
- Modify: `handler/BuiltInHandlers.kt`
- Test: `src/test/.../handler/NexoFurnitureHandlerTest.kt`

**Interfaces:**
- Consumes: `ChunkDataHandler`, `BuiltInData.NEXO_FURNITURE`, `FurnitureRecord`.
- Produces: `class NexoFurnitureHandler : ChunkDataHandler<List<FurnitureRecord>>`.

**Behaviour:**
- `isAvailable()` is true only when the `Nexo` plugin is enabled.
- `capture` keeps the base furniture entities that Nexo's API recognises (for example `NexoFurniture.isFurniture(entity)` / `furnitureMechanic(entity)`). It claims each one and claims Nexo's hitbox/interaction entities too. Each record holds the furniture id, the base entity location and yaw/pitch, and any Nexo state worth keeping (for example dye color or the rotation variant) as plain strings in `properties`.
- `restore` places the furniture through `NexoFurniture.place(id, location, yaw/rotation, blockFace)`, or the 1.29.1 equivalent, then reapplies `properties`.
- Every Nexo class reference stays inside this file. `BuiltInHandlers` instantiates it only when `Bukkit.getPluginManager().isPluginEnabled("Nexo")`, so the plugin loads without Nexo. Otherwise it registers an unavailable stub with the same key.

- [ ] **Step 1: Write the failing tests.** Mock Nexo statics with `mockkStatic`.
  - `` `captures and claims nexo furniture as neutral records` ``
  - `` `restore places furniture by id at the stored position and rotation` ``
  - `` `unavailable without nexo` ``
- [ ] **Step 2: Run** `./gradlew :surf-polar-paper-runtime:surf-polar-paper-runtime-paper:test --tests "*NexoFurniture*"`. Expected: FAIL.
- [ ] **Step 3: Implement.** Check the real Nexo 1.29.1 API in the cached jar (`com.nexomc/nexo/1.29.1`) with `javap`.
- [ ] **Step 4: Run** the same command. Expected: PASS. Then run `./gradlew build`.
- [ ] **Step 5: Commit.** `feat(paper): store nexo furniture as neutral chunk records`

---

### Task 10: Built-in CraftEngine furniture and custom block handlers

**Files:**
- Create: `runtime-paper/.../handler/CraftEngineFurnitureHandler.kt`, `handler/CraftEngineBlocksHandler.kt`
- Modify: `handler/BuiltInHandlers.kt`
- Test: `src/test/.../handler/CraftEngineHandlersTest.kt`

**Interfaces:**
- Consumes: as in Task 9, with `CRAFT_ENGINE_FURNITURE`, `CRAFT_ENGINE_BLOCKS` and `CustomBlockRecord`.
- Produces: two `ChunkDataHandler`s, both enabled by `BuiltInHandler.CRAFT_ENGINE`.

**Behaviour:**
- **Furniture:** the same contract as Task 9, using CraftEngine's furniture API.
- **Blocks:**
  - `capture` lists the CraftEngine custom blocks in the chunk with their id and state properties.
  - `restore` places them through CraftEngine's API.
  - Find where CraftEngine 26.9.1 keeps custom block state per chunk (its own world storage). Check whether it can be read and written through `CraftEngineBlocks` / `CEWorld` / `CEChunk` with `javap` on the cached `craft-engine-core`/`craft-engine-bukkit` jars.
  - **If custom blocks turn out to be plain vanilla block states with no extra per-chunk data, or the API cannot provide them, do not invent a workaround.** Report it as DONE_WITH_CONCERNS with the findings, and keep only the furniture handler.
- All CraftEngine classes stay inside these files, with the same availability gating as Nexo (the plugin name is `CraftEngine`).

- [ ] **Step 1: Write the failing tests.**
  - `` `captures and claims craftengine furniture as neutral records` ``
  - `` `restores furniture by id` ``
  - `` `captures and restores custom blocks by id and properties` ``
  - `` `unavailable without craftengine` ``
- [ ] **Step 2: Run** `./gradlew :surf-polar-paper-runtime:surf-polar-paper-runtime-paper:test --tests "*CraftEngine*"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS. Then run `./gradlew build`.
- [ ] **Step 5: Commit.** `feat(paper): store craftengine furniture and custom blocks as neutral chunk records`

---

### Task 11: Real-server integration tests, locally and in CI

**Files:**
- Create: module `surf-polar-paper-integration-test`, registered in `settings.gradle.kts`. It is a Paper test plugin with these files:
  - `IntegrationTestPlugin.kt`
  - `Scenario.kt`
  - `scenarios/*.kt`
  - `ResultWriter.kt`
- Create: `.github/workflows/integration-test.yml`
- Modify: `README.md` (how to run it locally, the `NEXO_JAR` variable, what CI covers, and the optional surf-core with the server name fallback)
- Modify: `runtime-paper/build.gradle.kts`, `runtime-paper/.../SurfPolarPaperInstancePaper.kt`
- Create: `runtime-paper/.../ServerNameResolver.kt`, plus its test

**Interfaces:**
- Consumes: the public API only (`SurfPolarPaperApi.create`, `WorldMigration`, `DataType`, `BuiltInHandler`, `BuiltInData`, the exceptions). The test plugin is a normal API consumer and does not use core or runtime internals.
- Produces: the Gradle task `:surf-polar-paper-integration-test:integrationTest`.
  1. It builds the plugin jars.
  2. It starts a real Paper 26.3 server without a console, using run-paper. The surf plugins already apply it, so use or extend the `RunServer` task.
  3. The server has DISK storage and locking off, so no RabbitMQ is needed.
  4. Plugins on the server:
     - surf-polar-paper
     - the test plugin
     - polarpaper
     - CommandAPI
     - surf-api-paper-server
     - CraftEngine
     - Nexo, only if the `NEXO_JAR` env var or the `-PnexoJar=<path>` property points to a jar
  5. It waits for `results.json`.
  6. It fails when any scenario failed or the server did not finish within 10 minutes.

**Plugin sources:**
- polarpaper, CommandAPI and CraftEngine are downloaded from their public release sources: GitHub releases, Modrinth or Hangar, at pinned versions.
- The surf jars come from `reposilite.slne.dev`.
- **surf-core-paper becomes an optional dependency** (user decision). Changes in runtime-paper:
  - Register surf-core as a soft server dependency, keeping its API compileOnly.
  - `SurfPolarPaperInstancePaper.serverName` resolves the name in this order:
    1. `SurfCoreApi.getCurrentServerName()` when the surf-core plugin is enabled. Put that call in a separate class so that SurfCore classes are never loaded when the plugin is missing.
    2. The env var `SURF_POLAR_PAPER_SERVER_NAME`.
    3. The system property `surf.polarpaper.server-name`.
    4. Otherwise, fail on enable with a message that names all three sources.
  - Add a unit test that covers the resolution order.
  - The integration server runs **without** surf-core-paper and with `SURF_POLAR_PAPER_SERVER_NAME=integration-test`.
  - Document all of this in the README.
- **If a required plugin cannot be obtained without credentials, report BLOCKED with the specifics.**

**Test plugin.**
- Its scenarios run sequentially after startup.
- Each scenario owns a separate game mode, so its versions and migrations are independent. A scenario that needs different migrations for the same world closes its instance and creates a new one with the new settings. This simulates a plugin update.
- Each scenario has the result `PASS`, `FAIL` (with the message and stack trace) or `SKIPPED` (with the reason).
- The plugin writes `results.json` and calls `Bukkit.shutdown()`.

**Scenarios:**
1. **`entities-and-pdc-round-trip`.** Spawn an armor stand with a PDC value, set a chunk PDC value, save, unload, load. Both survive. No player appears in `surf:entities`.
2. **`custom-data-type-round-trip`.** A plugin `DataType<List<String>>` set on a chunk and on the world survives save, unload and load.
3. **`raw-migration-strawberries`.**
   - At v1, place `minecraft:sweet_berry_bush` blocks.
   - Reopen at v2 with a raw migration that replaces them with `minecraft:air`.
   - After load, no berry bush is left and the stored version is 2.
4. **`migration-chain-1-to-100`.**
   - Create a world at v1.
   - Reopen at v100 with migrations for 2..100, leaving gaps at 50..59. Each one appends its version to a world `DataType<List<Int>>`, and every tenth one also changes a block.
   - Afterwards the list equals `(2..100) - (50..59)` and all block changes are present.
   - Reload at v100: no migration runs again.
5. **`live-migration`.**
   - At v1, spawn pigs in several chunks, including chunks never loaded during this session.
   - Reopen at v2 with a live migration that removes every pig.
   - No pig is left in any chunk.
6. **`newer-world-refused`.** Save at v5, reopen at v4 → `WorldVersionTooNewException`. The stored bytes are unchanged, and the world can be loaded again at v5.
7. **`failing-migration-leaves-storage-untouched`.** A migration at v3 throws → `WorldMigrationException(toVersion = 3)`. The stored bytes are byte-identical and the world is not loaded.
8. **`unhandled-section-aborts`.** A world saved with custom type X, reopened with an instance that does not register X → `UnhandledWorldDataException` naming X.
9. **`craftengine-round-trip`.** Place a CraftEngine furniture and a CraftEngine custom block (use the plugin's built-in default content, or ship a minimal pack in the test plugin's resources), save, unload, load. Both are back, and the furniture exists exactly once.
10. **`nexo-round-trip`.** The same as scenario 9, with Nexo furniture. SKIPPED without Nexo.
11. **`nexo-to-craftengine-to-nexo`.** SKIPPED unless both are installed.
    - At v1 with `nexoFurniture()`, place Nexo furniture A at known positions and rotations.
    - Reopen at v2 with `craftEngine()` only, plus a migration that converts `nexo:furniture` records into `craftengine:furniture` records through an id mapping table in the test plugin.
    - Assert that CraftEngine furniture is at the same positions and rotations, and that no Nexo furniture is left.
    - Reopen at v3 with `nexoFurniture()` only, plus the reverse migration.
    - Assert that Nexo furniture A is back at the same positions and rotations, that no CraftEngine furniture is left, and that each piece exists exactly once.

    The conversion is a raw stage because the records are neutral. That proves migrations run without the source plugin's API.

**CI workflow (`integration-test.yml`):**
- Triggers on pull requests and pushes to `feat/**` and `version/**`. It is a separate workflow and leaves `publish.yml` untouched.
- Java 25, with Gradle caching.
- It runs `./gradlew :surf-polar-paper-integration-test:integrationTest` without Nexo, so scenarios 10 and 11 report SKIPPED.
- It uploads `results.json` and the server log as artifacts.

- [ ] **Step 1: Write the scenarios and the harness first.** Run the task locally before Tasks 9 and 10 are fully verified in game, so that the failures show up. Expected: the task runs, the server starts, and `results.json` is written.
- [ ] **Step 2: Run** `./gradlew :surf-polar-paper-integration-test:integrationTest`, first without `NEXO_JAR` and then with `NEXO_JAR` pointing to the cached `com.nexomc/nexo/1.29.1` jar if it is a runnable plugin jar.
  - Expected: all non-Nexo scenarios PASS. With Nexo, all 11 PASS.
  - If the cached Nexo artifact is an API-only jar, note in the report that the Nexo scenarios were only verified as SKIPPED.
- [ ] **Step 3: Run** `./gradlew build`. It must not start the server: `integrationTest` is not part of `check`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 4: Commit.** `test: run real-server integration scenarios locally and in ci`
