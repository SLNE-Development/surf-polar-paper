package dev.slne.surf.polarpaper.runtime.microservice.database.tables

import dev.slne.surf.database.columns.nativeUuid
import dev.slne.surf.database.columns.time.offsetDateTime
import dev.slne.surf.database.table.AuditableLongIdTable

object WorldLockTable : AuditableLongIdTable("world_lock") {
    val worldUuid = nativeUuid("world_uuid")
    val gameModeIdentifier = varchar("game_mode_identifier", 255)
    val owner = varchar("owner", 255).index()
    val expiresAt = offsetDateTime("expires_at")

    init {
        uniqueIndex("uq_world_lock_world", worldUuid, gameModeIdentifier)
    }
}
