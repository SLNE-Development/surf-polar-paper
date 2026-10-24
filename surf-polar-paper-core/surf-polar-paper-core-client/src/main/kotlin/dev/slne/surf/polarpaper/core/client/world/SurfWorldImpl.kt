package dev.slne.surf.polarpaper.core.client.world

import dev.slne.surf.api.core.messages.adventure.buildText
import dev.slne.surf.polarpaper.api.world.SurfChunk
import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperApiImpl
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import net.kyori.adventure.text.Component
import org.bukkit.World
import java.util.*

/** Two worlds are equal when uuid and game mode match; version and data may change. */
class SurfWorldImpl(
    override val uuid: UUID,
    override val gameModeIdentifier: String,
    version: Int,
    override val api: SurfPolarPaperApiImpl,
    private val bridge: PlatformWorldBridge
) : SurfWorld {
    override var version: Int = version
        internal set

    override val name get() = uuid.toString()

    override val data = MapDataSections()

    override val bukkitWorld: World
        get() = bridge.bukkitWorld(this) ?: throw IllegalStateException("World $this is not loaded")

    private val displayName by lazy { buildText { variableValue("$gameModeIdentifier-$uuid") } }

    override fun chunk(x: Int, z: Int): SurfChunk? = bridge.chunk(this, x, z)

    override suspend fun save() = api.saveWorld(this)
    override suspend fun unload(saveChanges: Boolean) = api.unloadWorld(this, saveChanges)
    override suspend fun delete() = api.deleteWorld(this)

    override fun info() = WorldInfo(uuid, gameModeIdentifier)

    override fun asComponent(): Component = displayName

    override fun toString() = "SurfWorld(uuid=$uuid, gameModeIdentifier='$gameModeIdentifier', version=$version)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SurfWorld) return false

        return uuid == other.uuid && gameModeIdentifier == other.gameModeIdentifier
    }

    override fun hashCode() = 31 * uuid.hashCode() + gameModeIdentifier.hashCode()
}
