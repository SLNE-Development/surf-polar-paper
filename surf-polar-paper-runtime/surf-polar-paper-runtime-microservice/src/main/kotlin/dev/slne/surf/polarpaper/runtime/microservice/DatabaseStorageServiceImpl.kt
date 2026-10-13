package dev.slne.surf.polarpaper.runtime.microservice

import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.core.and
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.core.eq
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.deleteWhere
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.select
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.selectAll
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.upsert
import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.core.common.storage.database.DatabaseStorageService
import dev.slne.surf.polarpaper.runtime.microservice.database.tables.WorldStorageTable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import java.util.*

object DatabaseStorageServiceImpl : DatabaseStorageService {
    override suspend fun loadWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): ByteArray? = suspendTransaction {
        WorldStorageTable.selectAll()
            .where({ (WorldStorageTable.worldUuid eq worldUuid) and (WorldStorageTable.gameModeIdentifier eq gameModeIdentifier) })
            .map { it[WorldStorageTable.worldBytes] }
            .firstOrNull()
    }

    override suspend fun writeWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String,
        bytes: ByteArray
    ) = suspendTransaction {
        WorldStorageTable.upsert(
            where = { (WorldStorageTable.worldUuid eq worldUuid) and (WorldStorageTable.gameModeIdentifier eq gameModeIdentifier) },
            body = {
                it[WorldStorageTable.worldUuid] = worldUuid
                it[WorldStorageTable.gameModeIdentifier] = gameModeIdentifier
                it[WorldStorageTable.worldBytes] = bytes
            }
        )

        Unit
    }

    override suspend fun deleteWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    ) = suspendTransaction {
        WorldStorageTable.deleteWhere { (WorldStorageTable.worldUuid eq worldUuid) and (WorldStorageTable.gameModeIdentifier eq gameModeIdentifier) }

        Unit
    }

    override suspend fun listWorlds(
        gameModeIdentifier: String
    ): List<SurfWorld> = suspendTransaction {
        WorldStorageTable.select(
            listOf(
                WorldStorageTable.worldUuid,
                WorldStorageTable.gameModeIdentifier
            )
        )
            .where { WorldStorageTable.gameModeIdentifier eq gameModeIdentifier }
            .map {
                SurfWorld(
                    uuid = it[WorldStorageTable.worldUuid],
                    gameModeIdentifier = it[WorldStorageTable.gameModeIdentifier],
                    worldBytes = ByteArray(0) // Placeholder, actual bytes are not loaded in this query
                )
            }
            .toList()
    }
}