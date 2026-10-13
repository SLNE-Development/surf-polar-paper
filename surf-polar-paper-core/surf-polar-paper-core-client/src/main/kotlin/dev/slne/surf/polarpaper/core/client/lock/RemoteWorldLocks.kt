package dev.slne.surf.polarpaper.core.client.lock

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.polarpaper.api.world.WorldLockedException
import dev.slne.surf.polarpaper.core.common.lock.WorldLockService
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [WorldLocks] leased from the microservice. The leases of all held worlds are renewed periodically, so they only run
 * out when this server stops renewing them, e.g. because it crashed.
 */
class RemoteWorldLocks(
    private val owner: String,
    private val service: WorldLockService,
    private val renewInterval: Duration = WorldLockService.RENEW_INTERVAL_SECONDS.seconds
) : WorldLocks {
    private val log = logger()
    private val held = ConcurrentHashMap.newKeySet<WorldCacheKey>()
    private var renewJob: Job? = null

    /**
     * Starts renewing the held leases in [scope].
     */
    fun startRenewing(scope: CoroutineScope) {
        renewJob?.cancel()
        renewJob = scope.launch {
            while (isActive) {
                delay(renewInterval)
                renew()
            }
        }
    }

    override suspend fun lock(key: WorldCacheKey) {
        val result = service.tryLock(key.worldUuid, key.gameModeIdentifier, owner)

        if (!result.acquired) {
            throw WorldLockedException(key.worldUuid, key.gameModeIdentifier, result.owner)
        }

        held += key
    }

    override suspend fun unlock(key: WorldCacheKey) {
        held -= key
        service.unlock(key.worldUuid, key.gameModeIdentifier, owner)
    }

    override fun isHeld(key: WorldCacheKey) = key in held

    /**
     * Renews all leases and forgets the ones that were lost in the meantime.
     */
    suspend fun renew() {
        if (held.isEmpty()) return

        val stillHeld = try {
            service.renewLocks(owner).toSet()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // The leases stay valid until they expire, the next renewal may still be in time
            log.atWarning().withCause(exception).log("Failed to renew world locks")
            return
        }

        val lost = held.filterNot { it in stillHeld }
        if (lost.isNotEmpty()) {
            held.removeAll(lost.toSet())
            log.atSevere().log("Lost the locks of %s, these worlds will not be saved anymore", lost)
        }
    }

    override suspend fun close() {
        renewJob?.cancel()
        renewJob = null
        held.clear()

        service.unlockAll(owner)
    }
}
