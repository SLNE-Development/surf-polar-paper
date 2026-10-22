package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.world.WorldVersionTooNewException
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.ScenarioFailure
import dev.slne.surf.polarpaper.integration.check
import dev.slne.surf.polarpaper.integration.expectEquals
import java.util.UUID

/** A world saved at version 5 is refused at version 4, stays untouched and loads again at version 5. */
class NewerWorldRefused : Scenario("newer-world-refused") {
    override suspend fun run(context: ScenarioContext) {
        val newer = context.newApi { gameMode("newer") { version = 5 } }
        val uuid = UUID.randomUUID()
        val world = newer.createWorld(uuid, "newer")
        context.anchor(world.bukkitWorld, 0, 0)
        newer.saveWorld(world)
        context.closeApi(newer)

        val before = context.storedBytes("newer", uuid)

        val older = context.newApi { gameMode("newer") { version = 4 } }
        try {
            older.loadWorld(uuid, "newer")
            throw ScenarioFailure("Loading a version 5 world at version 4 did not fail")
        } catch (exception: WorldVersionTooNewException) {
            expectEquals(5, exception.storedVersion, "Stored version in the exception")
            expectEquals(4, exception.currentVersion, "Current version in the exception")
        }

        check(context.storedBytes("newer", uuid).contentEquals(before)) { "The refused load changed the stored bytes" }
        check(context.bukkitWorldOf(uuid) == null) { "The refused world is still loaded in Bukkit" }
        context.closeApi(older)

        val again = context.newApi { gameMode("newer") { version = 5 } }
        expectEquals(5, again.loadWorld(uuid, "newer").version, "Version after loading it again at 5")
    }
}
