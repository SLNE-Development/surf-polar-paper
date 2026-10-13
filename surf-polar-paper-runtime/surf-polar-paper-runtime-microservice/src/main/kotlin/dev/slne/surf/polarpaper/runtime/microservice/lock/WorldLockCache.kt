package dev.slne.surf.polarpaper.runtime.microservice.lock

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import dev.slne.surf.redis.libs.redisson.api.RedissonClient
import dev.slne.surf.redis.libs.redisson.client.codec.StringCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Short-lived copy of the lease owners, so lock requests for worlds held by someone else need no database query.
 * The database stays authoritative: a missing entry only means the database has to be asked.
 */
interface WorldLockCache {
    suspend fun owner(key: WorldCacheKey): String?
    suspend fun put(key: WorldCacheKey, owner: String, ttl: Duration)
    suspend fun remove(key: WorldCacheKey, owner: String)
}

/**
 * [WorldLockCache] in Valkey. Valkey being unavailable degrades to database-only locking instead of failing requests.
 */
class RedisWorldLockCache(private val redisson: RedissonClient) : WorldLockCache {
    private val log = logger()

    private fun bucket(key: WorldCacheKey) = redisson.getBucket<String>(
        "surf-polar-paper:world-lock:${key.gameModeIdentifier}:${key.worldUuid}",
        StringCodec.INSTANCE
    )

    override suspend fun owner(key: WorldCacheKey): String? = orNullOnFailure("read") {
        bucket(key).getAsync().await()
    }

    override suspend fun put(key: WorldCacheKey, owner: String, ttl: Duration) {
        orNullOnFailure("write") {
            bucket(key).setAsync(owner, ttl.toJavaDuration()).await()
        }
    }

    override suspend fun remove(key: WorldCacheKey, owner: String) {
        orNullOnFailure("remove") {
            // Only removes the entry while it still names this owner
            bucket(key).compareAndSetAsync(owner, null).await()
        }
    }

    private suspend inline fun <T> orNullOnFailure(action: String, block: () -> T): T? = try {
        block()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        log.atWarning().withCause(exception).log("Failed to $action the world lock cache, using the database only")
        null
    }
}
