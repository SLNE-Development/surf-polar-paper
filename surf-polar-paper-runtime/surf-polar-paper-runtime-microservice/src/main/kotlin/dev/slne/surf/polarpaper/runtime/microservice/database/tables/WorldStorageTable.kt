package dev.slne.surf.polarpaper.runtime.microservice.database.tables

import dev.slne.surf.database.columns.nativeUuid
import dev.slne.surf.database.table.AuditableLongIdTable

object WorldStorageTable : AuditableLongIdTable("world_storage") {
    val worldUuid = nativeUuid("world_uuid")
    val gameModeIdentifier = varchar("game_mode_identifier", 255)

    // blob maps to LONGBLOB on MariaDB and BYTEA on PostgreSQL, both large enough for whole worlds
    val worldBytes = blob("world_bytes")

    init {
        uniqueIndex("uq_world_storage_world", worldUuid, gameModeIdentifier)
    }
}
