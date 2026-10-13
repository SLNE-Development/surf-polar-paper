package dev.slne.surf.polarpaper.core.common.lock

import kotlinx.serialization.Serializable

/**
 * Outcome of [WorldLockService.tryLock]: [owner] is the server holding the lease, the requester if [acquired].
 */
@Serializable
data class WorldLockResult(
    val acquired: Boolean,
    val owner: String
)

