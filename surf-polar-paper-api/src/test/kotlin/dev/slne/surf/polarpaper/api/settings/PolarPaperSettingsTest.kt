package dev.slne.surf.polarpaper.api.settings

import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.migration.WorldMigration
import kotlinx.serialization.builtins.serializer
import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PolarPaperSettingsTest {
    private fun migration(to: Int) = object : WorldMigration(to) {}
    private fun type(key: String) = DataType(Key.key("test", key), String.serializer())

    @Test
    fun `builds game modes with version and migrations sorted by toVersion`() {
        val settings = polarPaperSettings {
            gameMode("skyblock") {
                version = 3
                migration(migration(3))
                migration(migration(2))
            }
        }

        val mode = settings.gameModes.getValue("skyblock")
        assertEquals(3, mode.currentVersion)
        assertEquals(listOf(2, 3), mode.migrations.map { it.toVersion })
    }

    @Test
    fun `rejects a migration above the current version`() {
        assertThrows<IllegalArgumentException> {
            polarPaperSettings { gameMode("skyblock") { version = 2; migration(migration(3)) } }
        }
    }

    @Test
    fun `rejects a migration to a version below 1`() {
        val exception = assertThrows<IllegalArgumentException> {
            polarPaperSettings { gameMode("skyblock") { version = 2; migration(migration(0)) } }
        }

        assertTrue(exception.message!!.contains("versions start at 1"), exception.message)
    }

    @Test
    fun `rejects two migrations to the same version`() {
        assertThrows<IllegalArgumentException> {
            polarPaperSettings {
                gameMode("skyblock") {
                    version = 3
                    migration(migration(3))
                    migration(migration(3))
                }
            }
        }
    }

    @Test
    fun `rejects a negative version`() {
        assertThrows<IllegalArgumentException> {
            polarPaperSettings { gameMode("skyblock") { version = -1 } }
        }
    }

    @Test
    fun `rejects an invalid game mode identifier`() {
        assertThrows<IllegalArgumentException> {
            polarPaperSettings { gameMode("../evil") { version = 1 } }
        }
    }

    @Test
    fun `rejects the same game mode twice`() {
        assertThrows<IllegalArgumentException> {
            polarPaperSettings {
                gameMode("skyblock") { version = 1 }
                gameMode("skyblock") { version = 2 }
            }
        }
    }

    @Test
    fun `rejects two data types or handlers with the same key`() {
        assertThrows<IllegalArgumentException> {
            polarPaperSettings { data(type("a")); data(type("a")) }
        }
        assertThrows<IllegalArgumentException> {
            polarPaperSettings {
                data(type("a"))
                handlers { add(handler(type("a"))) }
            }
        }
        assertThrows<IllegalArgumentException> {
            polarPaperSettings { handlers { add(handler(type("a"))); add(handler(type("a"))) } }
        }
    }

    @Test
    fun `rejects a custom type that reuses a built-in key`() {
        assertThrows<IllegalArgumentException> {
            polarPaperSettings { data(DataType(BuiltInData.ENTITIES.key, String.serializer())) }
        }
        assertThrows<IllegalArgumentException> {
            polarPaperSettings {
                handlers { add(handler(DataType(Key.key("nexo", "furniture"), String.serializer()))) }
            }
        }
    }

    @Test
    fun `autosave is off unless configured`() {
        assertNull(polarPaperSettings {}.autosaveInterval)
    }

    private fun handler(type: DataType<String>) = object : dev.slne.surf.polarpaper.api.data.ChunkDataHandler<String> {
        override val type = type
        override fun capture(
            chunk: org.bukkit.Chunk,
            entities: List<org.bukkit.entity.Entity>,
            claim: (org.bukkit.entity.Entity) -> Unit
        ): String? = null

        override fun restore(chunk: org.bukkit.Chunk, value: String) {}
    }
}
