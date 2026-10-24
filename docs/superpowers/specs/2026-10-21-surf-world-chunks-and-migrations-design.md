# SurfWorld / SurfChunk wrapper, chunk data handlers and world migrations

## Intent

surf-polar-paper stores Polar worlds as one opaque blob per `(worldUuid, gameMode)` and saves nothing but what
Polar writes by default. No entities are saved, no plugin data is saved, and nothing can change a world between
storage and the Bukkit world.

Game plugins need three things:

1. **Everything in a chunk survives a save.** That covers all entities, furniture from Nexo or CraftEngine, and
   data from our own systems such as multiblock structures.
2. **Worlds can change shape between releases.** Example: strawberries exist in world version 1 and are removed in
   version 2. Every stored world records the version it was saved at. The plugin that loads it says which version
   it runs. The system applies the ordered migrations in between before the world is used.
3. **Chunks can be read and changed in code**, both before the chunk is live (on raw Polar data) and after it is
   live (on the Bukkit chunk). This works inside migrations and also in normal gameplay code.

Nothing runs in production yet. The storage layout, the database schema, the CBOR envelope and the public API can
all change without backwards compatibility.

## Goals

- `SurfWorld` and `SurfChunk` wrappers around a Polar world and its chunks. They give typed access to keyed data
  sections per chunk and per world, plus raw block access for migrations.
- A per-plugin API instance is created through `SurfPolarPaperApi.create(plugin) { ... }`. It declares:
  - the game modes the plugin owns, each with a current world version and an ordered list of migrations
  - the data handlers its worlds use
  - autosave settings
- Built-in handlers for all entities, Nexo furniture and CraftEngine furniture. A public handler API covers our own
  data, such as multiblocks.
- World migrations keyed by an integer version. A migration has a raw stage and an optional live stage.
- Per-chunk hooks that gameplay code can subscribe to.
- Configurable periodic autosave per API instance.

## Non-goals

- Backwards compatibility with today's storage format, envelope or API (`SurfPolarPaperApi.INSTANCE` is removed).
- Downgrade migrations (going from version N down to N-1).
- Running migrations without a server. Migrations run when a world is loaded on a Paper server, never as an
  offline batch job.
- Platforms other than Paper.

## Anti-goals (failures even if it "works")

- **A migration that depends on a plugin being installed.** Example: a world uses Nexo in version 2 and switches
  to CraftEngine in version 3. Nexo is then no longer on the server. The v3 migration must still be able to read
  the Nexo furniture records and rewrite them as CraftEngine records. Stored handler data must therefore be a
  neutral, self-describing format (ids as plain strings, positions, rotation, properties). It must never require
  the owning plugin's classes or API to decode.
- **Silent data loss.** A section that cannot be handled must not be dropped quietly.
- **A partially migrated world in storage.** A failed migration must leave the stored world exactly as it was.
- **Furniture stored twice.** Entities that a furniture handler captures must not also land in the generic entity
  section.

## Decisions made by the user

### API creation and ownership

- Each plugin calls `SurfPolarPaperApi.create(plugin) { ... }` with a settings DSL. That call is the only entry
  point. The global singleton and companion delegate are removed. `listWorlds`, `loadWorld` and the rest move to the
  instance.
- Each game mode has exactly one owning plugin. Settings declare the game modes a plugin owns, each with its
  current version and its migrations. A second `create()` that claims an already-owned game mode fails. Only the
  owner can load, create, save or delete worlds of that game mode.
- Handlers are registered per API instance. Built-in handlers are opt-in in the settings, for example
  `handlers { entities(); nexoFurniture(); add(MultiblockHandler) }`. Worlds loaded through an instance know only
  that instance's handler keys.
- Autosave can be configured per instance at creation. Explicit `save` and `unload` keep working.

### Handlers and data sections

- Polar per-chunk `userData` and per-world `userData` become a container of keyed sections (`Key` → bytes).
  Examples: `surf:entities`, `nexo:furniture`, `craftengine:furniture`, `<plugin>:multiblocks`.
- Handlers use a **typed codec**. A handler declares a key and a kotlinx-serialization type. Handlers, migrations
  and gameplay code all read and write that typed value, for example
  `chunk.data(MultiblockHandler): List<Multiblock>?`. Migrations can also read a section by key and type without
  the handler being registered. This is how a v3 migration reads `nexo:furniture` with a copy of the record type
  and no Nexo.
- The Nexo and CraftEngine handlers ship with this plugin. Each writes neutral records (furniture id string, block
  position, rotation, extra properties as plain data). On save, each one claims its furniture entities so the
  generic entity handler skips them. On load, each one respawns its furniture through its plugin's API.
- **All non-player entities** in a chunk are saved, including their PDC, through the built-in entities handler.
  It is built on Polar's entity serializer.
- **If a chunk or world holds a section whose handler is not registered or not usable** (for example, Nexo is not
  installed) and no migration converted that section, **the world load is aborted**.
- Gameplay code can read and write section data on a loaded `SurfChunk` or `SurfWorld` at runtime, not only
  during save.
- Per-chunk hooks are available to gameplay code: "chunk loaded from Polar" (live chunk ready) and "chunk about to
  be saved".

### Versioning and migrations

- A world version is an integer. Every stored world records its version.
- Each owned game mode registers its current version and an ordered list of migrations. Migration N upgrades a
  world from N-1 to N.
- On load, the stored version is compared with the registered version:
  - **Stored equals registered:** the world loads normally.
  - **Stored is greater than registered** (an older plugin build): the load is refused.
  - **Stored is less than registered:** every step from stored+1 to registered is applied in order. **A step with
    no registered migration does nothing**, and the version simply moves past it. This holds for gaps in the
    middle of the chain too. Repairing the result is up to the developer.
- **A migration decides what changes.** It receives the whole chunk: blocks, block entities, all data sections,
  and the world-level data. It modifies them however it wants.
- **Stages:**
  - **Raw stage (preferred):** runs on the in-memory Polar data before Bukkit sees the world, for every chunk and
    for the world data. It can change blocks, block entities and sections.
  - **Live stage (only when needed, for example for entities):** if any pending step has a live stage, every chunk
    of the world is **force-loaded once at world load**. The live stages run on the live Bukkit chunks, in version
    order, before the world is handed back to the caller.
- **Any exception in any step aborts the world load.** Stored data stays untouched, and the error is logged.

## Constraints

- The project is Kotlin with the existing modules (`api`, `core-common`, `core-client`, `runtime-paper`,
  `runtime-microservice`), polarpaper 2.3.1, Folia-compatible coroutines (mccoroutine-folia), and the RabbitMQ RPC
  storage path.
- Polar's extension points are what the design builds on:
  - `PolarWorld.userData`, `PolarChunk.userData` / `withUserData`, `PolarWorld.chunks()` / `updateChunkAt`
  - `PolarWorldAccess`:
    - `saveChunkData(chunk, blockEntities, entities, writer)`
    - `loadChunkData(world, chunkAccess, bytes)`, which runs during generation before the chunk is live
    - `populateChunkData(bukkitChunk, bytes)`, which runs on the live chunk
  - `Polar.createWorld(PolarWorld, name, worldAccess)` and
    `Polar.saveWorld(world, chunks, source, worldAccess, selector, config)`
- Polar loads chunks into Bukkit on demand. A live stage therefore needs an explicit force-load of all chunks.
- World locks keep working as today: lock before load or create, refuse to save without the lock.

## Storage layout (my calls, adjust freely)

- The CBOR envelope gains `worldVersion: Int` next to `worldBytes`. The format version moves to 2 and the decoder
  only accepts 2.
- The version lives only in the envelope, so every backend (disk, S3, database) gets it without a schema change.
  The database schema stays as it is, and `WorldInfo` does not carry the version.
- The section container inside Polar `userData` has its own small versioned binary or CBOR format: a map from key
  string to bytes. Each section's bytes are that handler's kotlinx-serialization CBOR encoding.
- A newly created world starts at the game mode's current version, so it is never migrated.

## Other calls I made

- After all migrations succeed, the world is saved to storage at the new version immediately, before it is
  returned to the caller. A crash afterwards cannot re-run the migrations on top of migrated live data.
- If there are no pending live stages, the world is not force-loaded.
- A migration with only a live stage still runs its (empty) raw stage. Both stages see the same version step.
- The API module may depend on Paper types for `create(plugin)`, the live-stage and hook signatures, and the
  furniture handlers, because Paper is the only platform. The Polar-raw parts (`SurfChunk` raw access, sections)
  stay free of Bukkit and NMS where that is cheap.

## Settled after the builder review (my calls, approved in bulk)

- **Stage order when several steps are pending.** All raw stages run first, in version order, on the Polar data.
  The Bukkit world is then created once, every chunk is force-loaded, and all live stages run in version order. A
  live stage therefore sees the world after *all* raw stages. Raw stages handle block and data rewrites. Live stages
  handle what needs a live chunk or a plugin API, for example placing CraftEngine furniture from converted records.
- **When the unhandled-section check runs.**
  - With no live stage pending, it runs on the raw data after the raw stages, before Bukkit sees the world.
  - With a live stage pending, sections without a usable handler are kept as raw bytes in the chunk's in-memory
    section map while the world is live. Live stages can read and remove them by key and type. The check runs after
    all live stages over every chunk and the world data. On failure, the Bukkit world is unloaded without saving
    and storage is untouched.
- **`/sworld` admin commands.**
  - `list` and `join` work on any loaded or stored world.
  - `create`, `load`, `unload` and `delete` go through the API instance that owns the game mode, using its handlers
    and migrations, and fail with a clear message when no plugin owns it.
  - The `game-mode-identifier` config key is removed.
- **Chunk PDC.** The entities handler also stores the chunk's PDC (section `surf:chunk-pdc`), matching what Polar's
  default `EntitiesWorldAccess` already saved.
- **CraftEngine custom blocks.** CraftEngine keeps custom block data in its own per-world storage. The CraftEngine
  integration therefore covers custom blocks (section `craftengine:blocks`) as well as furniture, both as neutral
  records. If the CraftEngine API cannot provide this, the builder reports it rather than shipping silent loss.
- **Creating a world from template bytes.**
  - `createWorld` accepts Polar bytes. If the chunk `userData` is not our section container, it is treated as
    Polar's `EntitiesWorldAccess` format and imported into `surf:entities` / `surf:chunk-pdc`.
  - `createWorld` takes an optional `worldVersion`, defaulting to the game mode's current version. Templates
    authored at an older version are migrated like any loaded world.

## Left to the builder

- The exact shape of the settings DSL, the `SurfWorld`/`SurfChunk` method names, and the raw block-access helpers.
  The minimum is get/set block state by local coordinates, plus access to block entities.
- How Nexo and CraftEngine furniture is detected, captured and respawned through their APIs, and which properties
  each record keeps. The record format must stay plugin-independent, as described above.
- How the force-load is done (async chunk loads, concurrency limit, Folia region threads) and how chunk hooks are
  exposed (callbacks in settings, or Bukkit events).
- Autosave implementation details (interval default, whether disabled is the default, error handling per world).
- Test strategy. Follow the existing conventions: JUnit 5, `runTest`, MockK, the fakes in `core-client`, and
  Testcontainers for the microservice.
