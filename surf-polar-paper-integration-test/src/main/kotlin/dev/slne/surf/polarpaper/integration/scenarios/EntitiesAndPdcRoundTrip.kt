package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.integration.IntegrationTestPlugin
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.ScenarioFailure
import dev.slne.surf.polarpaper.integration.check
import dev.slne.surf.polarpaper.integration.expectEquals
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Minecart
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** Entities, their persistent data, a mount with its passenger and the chunk's persistent data survive a reload. */
class EntitiesAndPdcRoundTrip(plugin: IntegrationTestPlugin) : Scenario("entities-and-pdc-round-trip") {
    private val entityKey = NamespacedKey(plugin, "entity-marker")
    private val passengerKey = NamespacedKey(plugin, "passenger-marker")
    private val chunkKey = NamespacedKey(plugin, "chunk-marker")

    override suspend fun run(context: ScenarioContext) {
        val api = context.newApi {
            gameMode("entities-pdc") { version = 1 }
            handlers { entities() }
        }
        val uuid = UUID.randomUUID()
        val world = api.createWorld(uuid, "entities-pdc")
        val bukkit = world.bukkitWorld

        val chunk = context.anchor(bukkit, 0, 0)
        context.onMain {
            val base = Location(bukkit, 4.5, 62.0, 4.5)
            bukkit.spawn(base, ArmorStand::class.java) {
                it.setGravity(false)
                it.persistentDataContainer.set(entityKey, PersistentDataType.STRING, "stand")
            }

            val cart = bukkit.spawn(base.clone().add(3.0, 0.0, 0.0), Minecart::class.java) { it.setGravity(false) }
            val rider = bukkit.spawn(base.clone().add(3.0, 0.0, 0.0), ArmorStand::class.java) {
                it.setGravity(false)
                it.persistentDataContainer.set(passengerKey, PersistentDataType.STRING, "rider")
            }
            check(cart.addPassenger(rider)) { "The armor stand could not mount the minecart before saving" }

            chunk.persistentDataContainer.set(chunkKey, PersistentDataType.STRING, "chunk-value")
        }

        api.saveWorld(world)
        context.closeApi(api)

        val reloadApi = context.newApi {
            gameMode("entities-pdc") { version = 1 }
            handlers { entities() }
        }
        val reloaded = reloadApi.loadWorld(uuid, "entities-pdc")
        val reloadedChunk = context.loadChunk(reloaded.bukkitWorld, 0, 0)

        context.awaitTrue("the stored entities to be respawned") {
            reloadedChunk.entities.count { it is ArmorStand || it is Minecart } == 3
        }

        context.onMain {
            val stands = reloadedChunk.entities.filterIsInstance<ArmorStand>()
            val marked = stands.filter { it.persistentDataContainer.get(entityKey, PersistentDataType.STRING) == "stand" }
            expectEquals(
                1, marked.size,
                "Armor stands carrying the entity marker (persistent data keys of all stands: " +
                        "${stands.map { stand -> stand.persistentDataContainer.keys }})"
            )

            val cart = reloadedChunk.entities.filterIsInstance<Minecart>().singleOrNull()
                ?: throw ScenarioFailure("The minecart did not come back exactly once")
            val passenger = cart.passengers.singleOrNull()
            check(passenger is ArmorStand) { "The minecart lost its passenger, passengers: ${cart.passengers}" }
            expectEquals(
                "rider",
                (passenger as ArmorStand).persistentDataContainer.get(passengerKey, PersistentDataType.STRING),
                "Marker of the passenger"
            )
            expectEquals(
                "chunk-value",
                reloadedChunk.persistentDataContainer.get(chunkKey, PersistentDataType.STRING),
                "Chunk persistent data"
            )
        }

        // The stored section must not hold players
        val stored = reloaded.chunk(0, 0)?.data?.get(BuiltInData.ENTITIES)
            ?: throw ScenarioFailure("The chunk has no ${BuiltInData.ENTITIES.key} section")
        check(stored.none { it.type == "minecraft:player" }) { "A player was stored: ${stored.map { it.type }}" }
    }
}
