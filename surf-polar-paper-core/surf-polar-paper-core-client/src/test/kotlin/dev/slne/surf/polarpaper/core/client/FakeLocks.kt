package dev.slne.surf.polarpaper.core.client

import dev.slne.surf.polarpaper.core.client.lock.WorldLocks
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey

/** Simple [WorldLocks] that tracks which keys are held and how often unlock was called. */
class FakeLocks : WorldLocks {
    val held = mutableSetOf<WorldCacheKey>()
    var unlocks = 0

    override suspend fun lock(key: WorldCacheKey) {
        held += key
    }

    override suspend fun unlock(key: WorldCacheKey) {
        unlocks++
        held -= key
    }

    override fun isHeld(key: WorldCacheKey) = key in held

    override suspend fun close() {
        held.clear()
    }
}
