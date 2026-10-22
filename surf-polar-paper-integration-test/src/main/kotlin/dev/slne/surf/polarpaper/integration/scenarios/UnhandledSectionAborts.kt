package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.world.UnhandledWorldDataException
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.ScenarioFailure
import dev.slne.surf.polarpaper.integration.check
import kotlinx.serialization.builtins.serializer
import net.kyori.adventure.key.Key
import java.util.UUID

/** A world holding a section no registered type accounts for is not loaded, for a world and a chunk section. */
class UnhandledSectionAborts : Scenario("unhandled-section-aborts") {
    private val worldType = DataType(Key.key("integration", "world-only"), String.serializer())
    private val chunkType = DataType(Key.key("integration", "chunk-only"), String.serializer())

    override suspend fun run(context: ScenarioContext) {
        val writer = context.newApi {
            gameMode("unhandled") { version = 1 }
            data(worldType)
            data(chunkType)
        }
        val worldLevel = UUID.randomUUID()
        val chunkLevel = UUID.randomUUID()

        val first = writer.createWorld(worldLevel, "unhandled")
        first.data[worldType] = "world value"
        writer.saveWorld(first)

        val second = writer.createWorld(chunkLevel, "unhandled")
        context.anchor(second.bukkitWorld, 0, 0)
        second.chunk(0, 0)!!.data[chunkType] = "chunk value"
        writer.saveWorld(second)
        context.closeApi(writer)

        val reader = context.newApi { gameMode("unhandled") { version = 1 } }
        for ((uuid, type) in listOf(worldLevel to worldType, chunkLevel to chunkType)) {
            try {
                reader.loadWorld(uuid, "unhandled")
                throw ScenarioFailure("A world with the unregistered section ${type.key} was loaded")
            } catch (exception: UnhandledWorldDataException) {
                check(type.key in exception.keys) { "The exception names ${exception.keys} but not ${type.key}" }
            }
            check(context.bukkitWorldOf(uuid) == null) { "The aborted world $uuid is still loaded in Bukkit" }
        }
    }
}
