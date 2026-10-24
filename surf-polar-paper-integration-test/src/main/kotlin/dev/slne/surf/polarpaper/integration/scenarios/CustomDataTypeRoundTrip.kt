package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettingsBuilder
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.ScenarioFailure
import dev.slne.surf.polarpaper.integration.expectEquals
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import net.kyori.adventure.key.Key
import java.util.UUID

/** A plugin data type set on a chunk and on the world survives save, unload and load. */
class CustomDataTypeRoundTrip : Scenario("custom-data-type-round-trip") {
    private val tags = DataType(Key.key("integration", "tags"), ListSerializer(String.serializer()))

    override suspend fun run(context: ScenarioContext) {
        val settings: PolarPaperSettingsBuilder.() -> Unit = {
            gameMode("custom-data") { version = 1 }
            data(tags)
        }
        val api = context.newApi(settings)
        val uuid = UUID.randomUUID()
        val world = api.createWorld(uuid, "custom-data")

        context.anchor(world.bukkitWorld, 2, 3)
        world.chunk(2, 3)!!.data[tags] = listOf("alpha", "beta", "gamma")
        world.data[tags] = listOf("world-one", "world-two")

        api.saveWorld(world)
        context.closeApi(api)

        val reloaded = context.newApi(settings).loadWorld(uuid, "custom-data")
        context.loadChunk(reloaded.bukkitWorld, 2, 3)

        expectEquals(listOf("world-one", "world-two"), reloaded.data[tags], "World section")
        val chunk = reloaded.chunk(2, 3) ?: throw ScenarioFailure("Chunk 2, 3 is not live after loading it")
        expectEquals(listOf("alpha", "beta", "gamma"), chunk.data[tags], "Chunk section")
    }
}
