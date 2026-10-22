# surf-polar-paper

Stores and loads [Polar](https://github.com/MinehubMC/polarpaper) worlds for Paper servers. Worlds are kept per game mode
in one of three storages (disk, S3 or a database behind a microservice), and are locked so each world is only loaded on
one server at a time.

## Modules

| Module                                | Purpose                                                                                |
|---------------------------------------|----------------------------------------------------------------------------------------|
| `surf-polar-paper-api`                | Public API for other plugins, published to `reposilite.slne.dev/releases`              |
| `surf-polar-paper-core-common`        | RabbitMQ contracts shared by the plugin and the microservice                           |
| `surf-polar-paper-core-client`        | Storage providers, world locks and the API implementation                              |
| `surf-polar-paper-runtime-paper`      | The Paper plugin, attached to every GitHub release                                     |
| `surf-polar-paper-runtime-microservice` | Stores worlds in PostgreSQL and owns the world locks (PostgreSQL + Valkey)           |

## Using the API

```kotlin
repositories {
    maven("https://reposilite.slne.dev/releases")
}

dependencies {
    compileOnly("dev.slne.surf.polarpaper:surf-polar-paper-api:<version>")
}
```

Every plugin creates its own API instance. The instance owns the game modes it declares, and only it can load, create,
save or delete worlds of those game modes. A second plugin that declares an owned game mode fails with a
`GameModeAlreadyOwnedException`.

```kotlin
val api = SurfPolarPaperApi.create(this) {
    gameMode("skyblock") {
        version = 2
        migration(RemoveStrawberries)
    }

    handlers {
        entities()
        nexoFurniture()
        add(MultiblockHandler)
    }

    autosave(5.minutes)
}

val world = api.loadWorld(worldUuid, "skyblock")
val bukkitWorld = world.bukkitWorld

api.saveWorld(world)
api.unloadWorld(world)
```

Loading or creating a world that is loaded on another server throws a `WorldLockedException` naming that server.
When the plugin is disabled, its instances are closed: they save their worlds and free their game modes.

### Data sections and handlers

A world and each of its chunks hold keyed, typed data sections that are saved with the world. A `DataType` names a
section and its kotlinx-serialization type. Gameplay code can read and write sections on a loaded chunk or world:

```kotlin
val Multiblocks = DataType(Key.key("myplugin", "multiblocks"), ListSerializer(Multiblock.serializer()))

// in the settings: data(Multiblocks)
val blocks = world.chunk(x, z)?.data?.get(Multiblocks)
world.data[Multiblocks] = listOf(Multiblock(...))
```

A `ChunkDataHandler` captures plugin state into a section when the world is saved and restores it when the chunk
loads:

```kotlin
object MultiblockHandler : ChunkDataHandler<List<Multiblock>> {
    override val type = Multiblocks

    // Runs on the chunk's owning thread while the world is prepared for saving
    override fun capture(chunk: Chunk, entities: List<Entity>, claim: (Entity) -> Unit) =
        MultiblockRegistry.inChunk(chunk).ifEmpty { null }

    // Runs on the tick thread right after the chunk became live
    override fun restore(chunk: Chunk, value: List<Multiblock>) = MultiblockRegistry.restore(chunk, value)
}
```

Entities passed to `claim` are not saved by the entities handler, so furniture is never stored twice. Chunk hooks
(`onChunkLoad`, `onChunkSave` in the settings) run for every chunk.

Built-in handlers, enabled in the `handlers { }` block:

| Settings call      | Section keys                                            | Stores                                                    |
|--------------------|---------------------------------------------------------|-----------------------------------------------------------|
| `entities()`       | `surf:entities`, `surf:chunk-pdc`                       | All non-player entities that no handler claimed, and the chunk's persistent data |
| `nexoFurniture()`  | `nexo:furniture`                                        | Nexo furniture as neutral records (id, position, rotation, properties) |
| `craftEngine()`    | `craftengine:furniture`                                 | CraftEngine furniture as neutral records (`craftengine:blocks` is a reserved key, nothing is written to it) |

#### CraftEngine and Polar worlds

CraftEngine custom blocks are real block states, so they persist as plain Polar block states (id and properties) and
need no section. What a Polar world loses is CraftEngine's own per-chunk data:

- CraftEngine block entities (storage or ticking custom blocks) are inert after a load and their data is not persisted.
- Constant block renderers are lost unless CraftEngine rebuilds its sections from the block states.

CraftEngine configuration:

- Required: `chunk-system.sync-custom-blocks-on-chunk-load: true`. It rebuilds the sections and renderers from the
  block states on chunk load. It is a global option only. The furniture handler logs a warning when it is off.
- Recommended: list the Polar worlds in `chunk-system.blacklisted-worlds`, so stale `<world dir>/craftengine/` region
  data cannot overwrite Polar block states through `restore-custom-blocks-on-chunk-load`.

The records contain no classes of the furniture plugin, so a later migration can still read `nexo:furniture` when Nexo
is no longer installed.

A world that holds a section no registered type or usable handler accounts for is not loaded, so data is never dropped
quietly. A handler is usable when its plugin is installed.

### World versions and migrations

Every stored world records its version. The plugin that loads it declares the version it runs, and migration N upgrades
a world from N-1 to N:

- Stored equals current: the world loads as it is.
- Stored is greater than current (an older plugin build): the load is refused with a `WorldVersionTooNewException`.
- Stored is less than current: every step from stored+1 to current is applied in order. A step without a registered
  migration does nothing and the version moves past it, also for gaps in the middle.
- A world created without bytes starts at the current version. A template given to `createWorld` can name its own
  `worldVersion` and is migrated like any loaded world.

A migration has a raw stage and an optional live stage. The raw stage runs on the stored data before Bukkit sees the
world and should be preferred:

```kotlin
// Strawberries exist in version 1 and are removed in version 2
object RemoveStrawberries : WorldMigration(2) {
    override fun migrateRaw(world: RawWorld) {
        val minY = world.minSection * 16
        val maxY = (world.maxSection + 1) * 16 - 1

        for (chunk in world.chunks) {
            for (x in 0..15) for (z in 0..15) for (y in minY..maxY) {
                if (chunk.getBlock(x, y, z).startsWith("minecraft:sweet_berry_bush")) {
                    chunk.setBlock(x, y, z, "minecraft:air")
                }
            }
        }
    }
}
```

A migration that needs live chunks, for example to spawn entities, adds a live stage:

```kotlin
object ReplaceChairs : WorldMigration(3) {
    override val hasLiveStage get() = true

    override fun migrateLive(chunk: SurfChunk) {
        // Runs on the chunk's region thread, after all raw stages of all pending versions
    }
}
```

When several steps are pending, all raw stages run first in version order, then the platform world is created, every
chunk is force-loaded once (at most 32 at a time) and the live stages run in version order. Worlds without a live stage
are not force-loaded. Any exception aborts the load with a `WorldMigrationException` naming the version, and the stored
world stays as it was. After the migrations the world is saved at the new version before it is returned.

### Known limitations

- Polar drops chunks that only contain air when it saves, including their data sections. Data in an all-air chunk is lost.
- Polar writes the blocks of chunks that unloaded before a save, but knows nothing of their entities and data sections.
  Those are captured while the chunk unloads (see below) and written by the saves that follow.
- World user data that is not written by this plugin (Polar's own schematic offset) is dropped on the first save.
- Chunks that become live after the world loaded (generated ones) are registered when first seen and run no load hook.
- A chunk save hook must not start a save of the same world: the save lock of a world is not reentrant.
- When a chunk unloads while its world stays loaded, its entities and sections are captured at that moment and written
  by every later save, so they survive an unload between two saves. Chunks that unload together with their world, or
  because the server stops, are not captured: the world is saved before.
- Plugins that own game modes must `depend` or `softdepend` on Nexo and CraftEngine when they use the furniture
  handlers, so they are disabled before those plugins: on shutdown the worlds are saved while the owning plugin is
  disabled, and the furniture entities must still exist then. On Folia the shutdown save is best effort.
- Polar's own autosave is switched off for these worlds, because it would skip the preparation that captures the
  handler data. Use the `autosave(...)` setting of the API instance instead.

## Installing the plugin

Download `surf-polar-paper-runtime-paper-<version>-all.jar` from the
[releases](https://github.com/SLNE-Development/surf-polar-paper/releases) and put it into `plugins/` together with:

- [polarpaper](https://github.com/MinehubMC/polarpaper)
- surf-api-paper-server
- [surf-core-paper](https://github.com/SLNE-Development/surf-core/releases), optional: world locks are held under its
  server name (see below for the fallback without it)
- [CommandAPI](https://commandapi.jorel.dev)
- [surf-rabbitmq-paper](https://github.com/SLNE-Development/surf-rabbitmq/releases), needed for database storage and
  world locking

### Server name

The server name that world locks are held under is resolved in this order:

1. `SurfCoreApi.getCurrentServerName()`, when the surf-core-paper plugin is installed (the plugin is a soft dependency
   and none of its classes are loaded without it)
2. the environment variable `SURF_POLAR_PAPER_SERVER_NAME`
3. the system property `surf.polarpaper.server-name`

The plugin refuses to enable when none of them gives a name.

### Configuration

`plugins/surf-polar-paper-runtime-paper/polar-paper.yml`:

```yaml
storage:
    # DISK, DATABASE or S3. Only the matching section below is used.
    storage-type: S3
    disk:
        path: worlds
    s3:
        endpoint: https://s3.example.com
        region: us-east-1
        bucket: worlds
        access-key: ...
        secret-key: ...
    # Goes through RabbitMQ to the microservice, configured via the SURF_RABBITMQ_* variables
    database: {}
locking:
    # Locks worlds so each is only loaded on one server at a time. Needs RabbitMQ and the microservice.
    enabled: true
```

### Commands

All commands need the permission `surf.polarpaper.runtime.command.world`.

`create`, `load`, `unload` and `delete` act through the plugin that owns the game mode, with its handlers and
migrations, and fail with `No plugin owns game mode '<id>'` when there is none. `list` and `join` work on any loaded or
stored world.

| Command                                              | Description                                 |
|------------------------------------------------------|---------------------------------------------|
| `/sworld create <gameMode>`                          | Creates an empty world                      |
| `/sworld load <gameMode> <uuid>`                     | Loads a stored world                        |
| `/sworld join <gameMode> <uuid>`                     | Teleports you into a loaded world           |
| `/sworld unload <gameMode> <uuid>`                   | Saves and unloads a world                   |
| `/sworld delete <gameMode> <uuid>`                   | Deletes a world from storage                |
| `/sworld list <gameMode> [showLoadedOnly]`           | Lists stored worlds                         |

## World locking

The microservice keeps the authoritative lock in the PostgreSQL table `world_lock` and caches the current owner in
Valkey for 10 seconds, so requests for a world held by another server rarely reach the database. Several microservice
instances can run side by side, since all lock state lives in the shared database and Valkey.

A lock is a 60 second lease that the plugin renews every 20 seconds, so the worlds of a crashed server become free
within a minute. A server that lost a lock refuses to save that world instead of overwriting the new owner's changes.

## The microservice

`surf-polar-paper-runtime-microservice-<version>-all.jar` is configured through environment variables:

- `SURF_DATABASE_*` for PostgreSQL
- `SURF_RABBITMQ_*` for RabbitMQ
- `SURF_REDIS_HOST`, `SURF_REDIS_PORT`, `SURF_REDIS_PASSWORD` for Valkey

It is also published as a Docker image, built by CI from the Dockerfile that the microservice Gradle plugin generates
(`./gradlew generateMicroserviceDockerfile`):

```shell
docker pull ghcr.io/slne-development/surf-polar-paper-microservice:<version>
```

Docker's default seccomp profile blocks io_uring, so pass the Netty switches through `JAVA_OPTS`:
`-Dio.netty.transport.noNative=true -Ddev.slne.surf.rabbitmq.shaded.io.netty.transport.noNative=true -Ddev.slne.surf.redis.shaded.io.netty.transport.noNative=true`.

## Development

Requires Java 25. The microservice tests start PostgreSQL and Valkey with Testcontainers, so they need Docker.

```shell
./gradlew build
```

### Integration tests

`integrationTest` starts a real Paper 26.3 server and runs the scenarios of the module
`surf-polar-paper-integration-test`, a Paper plugin that only uses the public API. It is not part of `check` or `build`.

```shell
./gradlew :surf-polar-paper-integration-test:integrationTest
```

The task builds the plugin jars, lays out `surf-polar-paper-integration-test/build/integration-server` (disk storage,
world locking off, `SURF_POLAR_PAPER_SERVER_NAME=integration-test`, no surf-core-paper), downloads the pinned releases
of polarpaper, CommandAPI, LuckPerms, packetevents and CraftEngine and the surf-api-paper-server jar, and starts the
server without a console. The scenarios run after startup and write `results.json` in
`plugins/surf-polar-paper-integration-test/`. The server then stops with a modified world still loaded, and a second
start checks that this shutdown saved it. The task fails when a scenario failed or the server did not finish within 10
minutes (`-PserverTimeoutMinutes=<n>` changes that). Scenario results are `PASS`, `FAIL` or `SKIPPED`.

The Nexo scenarios need the premium Nexo plugin, which cannot be downloaded. Point the `NEXO_JAR` environment variable
(or `-PnexoJar=<path>`) at a Nexo jar to run them; without it they report `SKIPPED`. CI runs without Nexo
(`.github/workflows/integration-test.yml`, on pull requests and pushes to `feat/**` and `version/**`) and uploads
`results.json` and the server logs as artifacts.

## Releasing

Pushing to a `version/**` branch runs `.github/workflows/publish.yml`, which

- publishes the API to Reposilite,
- creates the GitHub release `v<version>` with the Paper plugin attached,
- pushes the microservice image to `ghcr.io/slne-development/surf-polar-paper-microservice` as `<version>` and `latest`.

The version comes from `gradle.properties`.
