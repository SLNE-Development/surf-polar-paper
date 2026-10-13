package dev.slne.surf.polarpaper.core.client.lock

import dev.slne.surf.polarpaper.api.world.WorldLockedException
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey

/**
 * The locks this server holds on worlds, so a world is only ever loaded on one server.
 */
interface WorldLocks {
    /**
     * Locks the world for this server.
     *
     * @throws WorldLockedException if another server holds the lock
     */
    suspend fun lock(key: WorldCacheKey)

    suspend fun unlock(key: WorldCacheKey)

    /**
     * Whether this server still holds the lock, `false` once it was lost, e.g. because it could not be renewed in time.
     */
    fun isHeld(key: WorldCacheKey): Boolean

    /**
     * Releases every lock of this server.
     */
    suspend fun close()
}

/**
 * Used when locking is disabled: every world counts as locked by this server.
 */
object DisabledWorldLocks : WorldLocks {
    override suspend fun lock(key: WorldCacheKey) {}
    override suspend fun unlock(key: WorldCacheKey) {}
    override fun isHeld(key: WorldCacheKey) = true
    override suspend fun close() {}
}
