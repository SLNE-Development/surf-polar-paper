package dev.slne.surf.polarpaper.runtime.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ServerNameResolverTest {
    private fun resolver(
        surfCore: String? = null,
        env: String? = null,
        property: String? = null,
        calls: MutableList<String> = mutableListOf()
    ) = ServerNameResolver(
        surfCoreName = { calls += "core"; surfCore },
        environment = { calls += "env"; env },
        property = { calls += "property"; property }
    )

    @Test
    fun `surf core wins over the environment and the property`() {
        assertEquals("core-1", resolver("core-1", "env-1", "prop-1").resolve())
    }

    @Test
    fun `the environment variable wins over the property when surf core is missing`() {
        assertEquals("env-1", resolver(null, "env-1", "prop-1").resolve())
    }

    @Test
    fun `the property is used last`() {
        assertEquals("prop-1", resolver(null, null, "prop-1").resolve())
    }

    @Test
    fun `later sources are not read once one answered`() {
        val calls = mutableListOf<String>()
        resolver(null, "env-1", "prop-1", calls).resolve()

        assertEquals(listOf("core", "env"), calls)
    }

    @Test
    fun `blank values are skipped`() {
        assertEquals("prop-1", resolver("  ", "", "prop-1").resolve())
    }

    @Test
    fun `values are trimmed`() {
        assertEquals("env-1", resolver(null, " env-1 ", null).resolve())
    }

    @Test
    fun `failure names all three sources`() {
        val message = assertThrows<IllegalStateException> { resolver().resolve() }.message.orEmpty()

        assertTrue("surf-core-paper" in message, message)
        assertTrue(ServerNameResolver.ENVIRONMENT_VARIABLE in message, message)
        assertTrue(ServerNameResolver.SYSTEM_PROPERTY in message, message)
    }

    @Test
    fun `the names of the fallbacks are fixed`() {
        assertEquals("SURF_POLAR_PAPER_SERVER_NAME", ServerNameResolver.ENVIRONMENT_VARIABLE)
        assertEquals("surf.polarpaper.server-name", ServerNameResolver.SYSTEM_PROPERTY)
    }
}
