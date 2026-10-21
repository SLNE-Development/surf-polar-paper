package dev.slne.surf.polarpaper.runtime.paper

import com.google.auto.service.AutoService
import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.world.SurfChunk
import dev.slne.surf.polarpaper.core.client.world.PlatformWorldBridge
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import org.bukkit.World

// Stubbed so the module compiles; the real implementation is written in Task 8.
@AutoService(PlatformWorldBridge::class)
class PlatformWorldBridgePaper : PlatformWorldBridge {
    override suspend fun loadWorld(world: SurfWorldImpl, polarBytes: ByteArray?, migrations: List<WorldMigration>) =
        TODO("Task 8")

    override suspend fun saveWorld(world: SurfWorldImpl): ByteArray = TODO("Task 8")

    override suspend fun unloadWorld(world: SurfWorldImpl) = TODO("Task 8")

    override fun bukkitWorld(world: SurfWorldImpl): World? = TODO("Task 8")

    override fun chunk(world: SurfWorldImpl, x: Int, z: Int): SurfChunk? = TODO("Task 8")
}
