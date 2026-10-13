package dev.slne.surf.polarpaper.core.common.storage.database

import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.rabbitmq.api.rpc.RpcService
import java.util.*

@RpcService
interface DatabaseStorageService {
    suspend fun loadWorldBytes(worldUuid: UUID, gameModeIdentifier: String): ByteArray?
    suspend fun writeWorldBytes(worldUuid: UUID, gameModeIdentifier: String, bytes: ByteArray)

    suspend fun deleteWorldBytes(worldUuid: UUID, gameModeIdentifier: String)
    suspend fun listWorlds(gameModeIdentifier: String): List<WorldInfo>
}
