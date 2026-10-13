package dev.slne.surf.polarpaper.core.common.world

import dev.slne.surf.api.core.serializer.java.uuid.SerializableUUID
import dev.slne.surf.polarpaper.api.world.SurfWorld
import kotlinx.serialization.Serializable

@Serializable
data class WorldCacheKey(
    val worldUuid: SerializableUUID,
    val gameModeIdentifier: String
)

fun SurfWorld.key() = WorldCacheKey(this.uuid, this.gameModeIdentifier)