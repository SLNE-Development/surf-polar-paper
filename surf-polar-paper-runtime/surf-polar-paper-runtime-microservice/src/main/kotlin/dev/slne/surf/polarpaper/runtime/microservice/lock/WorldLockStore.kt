package dev.slne.surf.polarpaper.runtime.microservice.lock

import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.core.and
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.core.eq
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.core.greaterEq
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.core.less
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.core.or
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.deleteWhere
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.insert
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.select
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.update
import dev.slne.surf.polarpaper.core.common.lock.WorldLockResult
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import dev.slne.surf.polarpaper.runtime.microservice.database.tables.WorldLockTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import java.time.OffsetDateTime

/**
 * The authoritative world leases in the database. Every change is a single atomic statement, so several microservice
 * instances can share the table without granting a lease twice.
 */
class WorldLockStore {
    suspend fun tryAcquire(
        key: WorldCacheKey,
        owner: String,
        now: OffsetDateTime,
        expiresAt: OffsetDateTime
    ): WorldLockResult {
        // Extends the own lease or takes over an expired one
        val updated = suspendTransaction {
            WorldLockTable.update({
                matches(key) and ((WorldLockTable.owner eq owner) or (WorldLockTable.expiresAt less now))
            }) {
                it[WorldLockTable.owner] = owner
                it[WorldLockTable.expiresAt] = expiresAt
            }
        }
        if (updated > 0) {
            return WorldLockResult(acquired = true, owner = owner)
        }

        // No lease row yet. The unique index makes concurrent inserts fail for all but one instance.
        val insertFailure = try {
            suspendTransaction {
                WorldLockTable.insert {
                    it[WorldLockTable.worldUuid] = key.worldUuid
                    it[WorldLockTable.gameModeIdentifier] = key.gameModeIdentifier
                    it[WorldLockTable.owner] = owner
                    it[WorldLockTable.expiresAt] = expiresAt
                }
            }
            return WorldLockResult(acquired = true, owner = owner)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            exception
        }

        val holder = currentOwner(key)
            ?: throw IllegalStateException("Failed to lock world $key", insertFailure)

        return WorldLockResult(acquired = holder == owner, owner = holder)
    }

    suspend fun renew(owner: String, now: OffsetDateTime, expiresAt: OffsetDateTime): List<WorldCacheKey> =
        suspendTransaction {
            val active = (WorldLockTable.owner eq owner) and (WorldLockTable.expiresAt greaterEq now)

            WorldLockTable.update({ active }) {
                it[WorldLockTable.expiresAt] = expiresAt
            }

            WorldLockTable.select(WorldLockTable.worldUuid, WorldLockTable.gameModeIdentifier)
                .where { active }
                .map { WorldCacheKey(it[WorldLockTable.worldUuid], it[WorldLockTable.gameModeIdentifier]) }
                .toList()
        }

    suspend fun release(key: WorldCacheKey, owner: String): Boolean = suspendTransaction {
        WorldLockTable.deleteWhere { matches(key) and (WorldLockTable.owner eq owner) } > 0
    }

    suspend fun releaseAll(owner: String): List<WorldCacheKey> = suspendTransaction {
        val held = WorldLockTable.select(WorldLockTable.worldUuid, WorldLockTable.gameModeIdentifier)
            .where { WorldLockTable.owner eq owner }
            .map { WorldCacheKey(it[WorldLockTable.worldUuid], it[WorldLockTable.gameModeIdentifier]) }
            .toList()

        WorldLockTable.deleteWhere { WorldLockTable.owner eq owner }

        held
    }

    private suspend fun currentOwner(key: WorldCacheKey): String? = suspendTransaction {
        WorldLockTable.select(WorldLockTable.owner)
            .where { matches(key) }
            .map { it[WorldLockTable.owner] }
            .firstOrNull()
    }

    private fun matches(key: WorldCacheKey) =
        (WorldLockTable.worldUuid eq key.worldUuid) and (WorldLockTable.gameModeIdentifier eq key.gameModeIdentifier)
}
