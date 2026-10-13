package dev.slne.surf.polarpaper.core.common.lock

import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import dev.slne.surf.rabbitmq.api.rpc.RpcService
import java.util.*

/**
 * Leases worlds to one server at a time, so a world is never loaded on two servers at once.
 *
 * A lease runs out after [LEASE_SECONDS] unless its owner renews it, so the worlds of a crashed server become available
 * again on their own. Owners renew all their leases every [RENEW_INTERVAL_SECONDS].
 */
@RpcService
interface WorldLockService {
    /**
     * Takes or extends the lease of the world for [owner], unless another owner holds an active lease.
     */
    suspend fun tryLock(worldUuid: UUID, gameModeIdentifier: String, owner: String): WorldLockResult

    /**
     * Extends every active lease of [owner] and returns the worlds it still holds.
     */
    suspend fun renewLocks(owner: String): List<WorldCacheKey>

    /**
     * Releases the lease of the world if [owner] holds it.
     */
    suspend fun unlock(worldUuid: UUID, gameModeIdentifier: String, owner: String)

    /**
     * Releases every lease of [owner].
     */
    suspend fun unlockAll(owner: String)

    companion object {
        const val LEASE_SECONDS = 60L
        const val RENEW_INTERVAL_SECONDS = 20L
    }
}
