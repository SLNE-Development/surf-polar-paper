package dev.slne.surf.polarpaper.runtime.microservice.database.tables

import dev.slne.surf.database.columns.nativeUuid
import dev.slne.surf.database.table.AuditableLongIdTable

object WorldStorageTable : AuditableLongIdTable("world_storage") {
    val worldUuid = nativeUuid("world_uuid").uniqueIndex()
    val gameModeIdentifier = varchar("game_mode_identifier", 255)

    val worldBytes = binary("world_bytes", 50_000_000)
}