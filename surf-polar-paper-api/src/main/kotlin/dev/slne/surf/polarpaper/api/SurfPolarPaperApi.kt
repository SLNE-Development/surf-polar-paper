package dev.slne.surf.polarpaper.api

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettings
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettingsBuilder
import dev.slne.surf.polarpaper.api.settings.polarPaperSettings
import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.api.world.WorldInfo
import it.unimi.dsi.fastutil.objects.ObjectSet
import org.bukkit.plugin.Plugin
import org.jetbrains.annotations.UnmodifiableView
import java.util.*

/**
 * The world API of one plugin. Every plugin creates its own instance with [create] and owns the game modes
 * declared in its [settings]. All operations only work on those game modes.
 */
interface SurfPolarPaperApi {
    val plugin: Plugin
    val settings: PolarPaperSettings

    /** The loaded worlds of this instance only. */
    val loadedWorlds: @UnmodifiableView ObjectSet<SurfWorld>

    suspend fun listWorlds(
        gameModeIdentifier: String,
        showLoadedOnly: Boolean = false
    ): List<WorldInfo>

    /**
     * Creates and loads a world. [polarBytes] is a Polar template, null creates an empty world.
     * The world is stamped with the game mode's current version unless [worldVersion] names the version of the
     * template, in which case the migrations above it run.
     */
    suspend fun createWorld(
        worldUuid: UUID,
        gameModeIdentifier: String,
        polarBytes: ByteArray? = null,
        worldVersion: Int? = null
    ): SurfWorld

    suspend fun loadWorld(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): SurfWorld

    suspend fun saveWorld(world: SurfWorld)

    /**
     * Saves every loaded world and keeps them loaded. Failures do not stop the remaining worlds from being saved,
     * they are thrown together afterwards.
     */
    suspend fun saveAllWorlds()

    suspend fun unloadWorld(
        world: SurfWorld,
        saveChanges: Boolean = true
    )

    suspend fun unloadAllWorlds(saveChanges: Boolean = true)

    suspend fun deleteWorld(world: SurfWorld)

    fun findWorld(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): SurfWorld?

    /**
     * Stops autosave, saves and unloads the worlds of this instance, releases their locks and frees its game modes.
     * A world Bukkit refuses to unload (for example one with players) stays loaded, saved and locked. Further calls
     * on this instance throw. Safe to call twice.
     *
     * The instance is closed automatically when its plugin is disabled; at server stop that close only saves the
     * worlds, because the server has already closed its world manager.
     */
    suspend fun close()

    companion object {
        // Resolved on first use so that loading this class does not need the implementation
        private val factory by lazy { requiredService<SurfPolarPaperApiFactory>() }

        /**
         * Creates the API instance of [plugin].
         *
         * @throws dev.slne.surf.polarpaper.api.world.GameModeAlreadyOwnedException if a game mode is owned by another plugin
         */
        fun create(plugin: Plugin, settings: PolarPaperSettingsBuilder.() -> Unit): SurfPolarPaperApi =
            factory.create(plugin, polarPaperSettings(settings))
    }
}
