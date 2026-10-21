package dev.slne.surf.polarpaper.core.client

import dev.slne.surf.polarpaper.api.settings.PolarPaperSettings
import dev.slne.surf.polarpaper.api.world.GameModeAlreadyOwnedException
import dev.slne.surf.polarpaper.core.client.ownership.GameModeOwnership
import org.bukkit.plugin.Plugin
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registry of the live API instances and the game mode ownership across them.
 * Claiming, registering and releasing are synchronized so ownership means exactly one live instance.
 */
object ApiInstances {
    val ownership = GameModeOwnership()
    private val instances = CopyOnWriteArrayList<SurfPolarPaperApiImpl>()

    val all: List<SurfPolarPaperApiImpl> get() = instances.toList()

    /**
     * Claims every game mode of [settings] for [plugin], then creates and registers the instance.
     * Nothing is claimed or registered when a game mode is already declared by another live instance,
     * including one of the same plugin.
     */
    @Synchronized
    fun claimAndRegister(
        plugin: Plugin,
        settings: PolarPaperSettings,
        create: () -> SurfPolarPaperApiImpl
    ): SurfPolarPaperApiImpl {
        settings.gameModes.keys.forEach { id ->
            if (ownerOf(id) != null) throw GameModeAlreadyOwnedException(id, plugin.name)
        }

        try {
            settings.gameModes.keys.forEach { ownership.claim(it, plugin.name) }
        } catch (exception: Exception) {
            restoreClaims(plugin.name)
            throw exception
        }

        return create().also { instances += it }
    }

    @Synchronized
    fun register(api: SurfPolarPaperApiImpl) {
        instances += api
    }

    @Synchronized
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
    @Synchronized
    fun restoreClaims(owner: String) {
        ownership.release(owner)
        instances.filter { it.plugin.name == owner }
            .forEach { api -> api.settings.gameModes.keys.forEach { ownership.claim(it, owner) } }
    }
}
