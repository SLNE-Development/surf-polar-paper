package dev.slne.surf.polarpaper.api.world

import dev.slne.surf.api.core.messages.adventure.buildText
import dev.slne.surf.api.core.serializer.java.uuid.SerializableUUID
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike

/**
 * A world identified by its [uuid] and [gameModeIdentifier]. Two worlds are equal when both match,
 * regardless of their current [worldBytes].
 */
@Serializable
data class SurfWorld(
    val uuid: SerializableUUID,
    val gameModeIdentifier: String,
    var worldBytes: ByteArray
) : ComponentLike {
    val name get() = uuid.toString()

    @Transient
    private val displayName = buildText {
        variableValue("$gameModeIdentifier-$uuid")
    }

    suspend fun save() {
        SurfPolarPaperApi.saveWorld(this)
    }

    suspend fun delete() {
        SurfPolarPaperApi.deleteWorld(this)
    }

    fun <W> platformWorld(): W? {
        return SurfPolarPaperApi.getPlatformWorld(this)
    }

    fun info() = WorldInfo(uuid, gameModeIdentifier)

    override fun toString(): String {
        return "SurfWorld(uuid=$uuid, gameModeIdentifier='$gameModeIdentifier', worldBytes=${worldBytes.size} bytes)"
    }

    override fun asComponent(): Component {
        return displayName
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SurfWorld) return false

        return uuid == other.uuid && gameModeIdentifier == other.gameModeIdentifier
    }

    override fun hashCode(): Int {
        return 31 * uuid.hashCode() + gameModeIdentifier.hashCode()
    }
}
