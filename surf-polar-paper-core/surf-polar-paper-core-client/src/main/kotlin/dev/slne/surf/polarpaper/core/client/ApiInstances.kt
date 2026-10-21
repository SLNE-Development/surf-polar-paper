package dev.slne.surf.polarpaper.core.client

import dev.slne.surf.polarpaper.core.client.ownership.GameModeOwnership
import org.bukkit.plugin.Plugin
import java.util.concurrent.CopyOnWriteArrayList

/** Registry of the live API instances and the game mode ownership across them. */
object ApiInstances {
    val ownership = GameModeOwnership()
    private val instances = CopyOnWriteArrayList<SurfPolarPaperApiImpl>()

    val all: List<SurfPolarPaperApiImpl> get() = instances.toList()

    fun register(api: SurfPolarPaperApiImpl) {
        instances += api
    }

    fun unregister(api: SurfPolarPaperApiImpl) {
        instances -= api
        restoreClaims(api.plugin.name)
    }

    fun ownerOf(gameModeIdentifier: String): SurfPolarPaperApiImpl? =
        instances.firstOrNull { gameModeIdentifier in it.settings.gameModes }

    fun forPlugin(plugin: Plugin): List<SurfPolarPaperApiImpl> = instances.filter { it.plugin.name == plugin.name }

    /**
     * Frees every claim of [owner] and claims again what its remaining instances declare, because
     * [GameModeOwnership.release] works per owner and not per instance.
     */
    fun restoreClaims(owner: String) {
        ownership.release(owner)
        instances.filter { it.plugin.name == owner }
            .forEach { api -> api.settings.gameModes.keys.forEach { ownership.claim(it, owner) } }
    }
}
