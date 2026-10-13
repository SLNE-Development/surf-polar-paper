package dev.slne.surf.polarpaper.api.world

import dev.slne.surf.api.core.messages.adventure.buildText
import dev.slne.surf.api.core.serializer.java.uuid.SerializableUUID
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import kotlinx.serialization.Serializable
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike

@Serializable
data class SurfWorld(
    val uuid: SerializableUUID,
    val gameModeIdentifier: String,
    var worldBytes: ByteArray
) : ComponentLike {
    val name = uuid.toString()

    suspend fun save() {
        SurfPolarPaperApi.saveWorld(this)
    }

    suspend fun delete() {
        SurfPolarPaperApi.deleteWorld(this)
    }

    fun <W> platformWorld(): W? {
        return SurfPolarPaperApi.getPlatformWorld(this)
    }

    override fun toString(): String {
        return "SurfWorld(uuid=$uuid, gameModeIdentifier='$gameModeIdentifier', worldBytes=${worldBytes.size} bytes)"
    }

    private val displayName = buildText {
        variableValue("$gameModeIdentifier-$uuid")
    }

    override fun asComponent(): Component {
        return displayName
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SurfWorld

        if (uuid != other.uuid) return false
        if (gameModeIdentifier != other.gameModeIdentifier) return false
        if (!worldBytes.contentEquals(other.worldBytes)) return false
        if (displayName != other.displayName) return false

        return true
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + gameModeIdentifier.hashCode()
        result = 31 * result + worldBytes.contentHashCode()
        result = 31 * result + displayName.hashCode()
        return result
    }
}