package dev.slne.surf.polarpaper.runtime.microservice.lock

import dev.slne.surf.polarpaper.core.common.lock.WorldLockResult
import dev.slne.surf.polarpaper.core.common.lock.WorldLockService
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import java.time.Clock
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Answers lock requests from [cache] when it names another owner and from the database otherwise.
 */
class WorldLockServiceImpl(
    private val store: WorldLockStore,
    private val cache: WorldLockCache,
    private val clock: Clock = Clock.systemUTC(),
    private val lease: Duration = WorldLockService.LEASE_SECONDS.seconds,
    private val cacheTtl: Duration = CACHE_TTL
) : WorldLockService {
    override suspend fun tryLock(worldUuid: UUID, gameModeIdentifier: String, owner: String): WorldLockResult {
        val key = WorldCacheKey(worldUuid, gameModeIdentifier)

        val cachedOwner = cache.owner(key)
        if (cachedOwner != null && cachedOwner != owner) {
            return WorldLockResult(acquired = false, owner = cachedOwner)
        }

        val now = now()
        val result = store.tryAcquire(key, owner, now, now + lease)
        cache.put(key, result.owner, cacheTtl)

        return result
    }

    override suspend fun renewLocks(owner: String): List<WorldCacheKey> {
        val now = now()
        val held = store.renew(owner, now, now + lease)

        held.forEach { cache.put(it, owner, cacheTtl) }

        return held
    }

    override suspend fun unlock(worldUuid: UUID, gameModeIdentifier: String, owner: String) {
        val key = WorldCacheKey(worldUuid, gameModeIdentifier)

        store.release(key, owner)
        cache.remove(key, owner)
    }

    override suspend fun unlockAll(owner: String) {
        store.releaseAll(owner).forEach { cache.remove(it, owner) }
    }

    // Databases store timestamps with microsecond precision
    private fun now() = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)

    private operator fun OffsetDateTime.plus(duration: Duration): OffsetDateTime = plus(duration.toJavaDuration())

    companion object {
        /** Cache entries expire long before a lease, so a crashed owner blocks requests for at most this long. */
        val CACHE_TTL = 10.seconds
    }
}
