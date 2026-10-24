package dev.slne.surf.polarpaper.api.migration

import dev.slne.surf.polarpaper.api.world.SurfChunk

/**
 * Migrates a world to [toVersion]. A migration may have a raw stage, a live stage, or both.
 */
abstract class WorldMigration(val toVersion: Int) {
    /** Runs on the stored world before any chunk is loaded. */
    open fun migrateRaw(world: RawWorld) {}

    /** Whether [migrateLive] must run for this migration. */
    open val hasLiveStage: Boolean get() = false

    /**
     * Runs for every chunk after all raw stages, on that chunk's region thread. The world is still being loaded, so
     * this must not save, unload or delete it through the API (that call would wait on the load forever).
     */
    open fun migrateLive(chunk: SurfChunk) {}
}
