package dev.slne.surf.polarpaper.core.client.fakes

import dev.slne.surf.polarpaper.core.common.lock.WorldLockResult
import dev.slne.surf.polarpaper.core.common.lock.WorldLockService
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import java.util.*

/**
 * In-memory [WorldLockService] without expiry. [failRenewals] simulates an unreachable microservice.
 */
class FakeWorldLockService : WorldLockService {
    val owners = mutableMapOf<WorldCacheKey, String>()
    var renewals = 0
    var failRenewals = false

    override suspend fun tryLock(worldUuid: UUID, gameModeIdentifier: String, owner: String): WorldLockResult {
        val holder = owners.getOrPut(WorldCacheKey(worldUuid, gameModeIdentifier)) { owner }
        return WorldLockResult(acquired = holder == owner, owner = holder)
    }

    override suspend fun renewLocks(owner: String): List<WorldCacheKey> {
        renewals++
        if (failRenewals) error("Microservice unreachable")

        return owners.filterValues { it == owner }.keys.toList()
    }

    override suspend fun unlock(worldUuid: UUID, gameModeIdentifier: String, owner: String) {
        owners.remove(WorldCacheKey(worldUuid, gameModeIdentifier), owner)
    }

    override suspend fun unlockAll(owner: String) {
        owners.values.removeAll { it == owner }
    }
}
