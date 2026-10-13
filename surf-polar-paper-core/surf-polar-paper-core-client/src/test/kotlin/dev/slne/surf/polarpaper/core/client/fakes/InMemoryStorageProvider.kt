package dev.slne.surf.polarpaper.core.client.fakes

import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import java.util.*

class InMemoryStorageProvider : StorageProvider {
    val stored = mutableMapOf<WorldCacheKey, ByteArray>()
    var loads = 0
    var writes = 0
    var connected = false
    var failOnConnect: Exception? = null

    override suspend fun connect() {
        failOnConnect?.let { throw it }
        connected = true
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun listWorlds(gameModeIdentifier: String): List<WorldInfo> =
        stored.keys
            .filter { it.gameModeIdentifier == gameModeIdentifier }
            .map { WorldInfo(it.worldUuid, it.gameModeIdentifier) }

    override suspend fun loadWorldBytes(worldUuid: UUID, gameModeIdentifier: String): ByteArray? {
        loads++
        return stored[WorldCacheKey(worldUuid, gameModeIdentifier)]
    }

    override suspend fun writeWorldBytes(worldUuid: UUID, gameModeIdentifier: String, bytes: ByteArray) {
        writes++
        stored[WorldCacheKey(worldUuid, gameModeIdentifier)] = bytes
    }

    override suspend fun deleteWorldBytes(worldUuid: UUID, gameModeIdentifier: String) {
        stored.remove(WorldCacheKey(worldUuid, gameModeIdentifier))
    }
}
