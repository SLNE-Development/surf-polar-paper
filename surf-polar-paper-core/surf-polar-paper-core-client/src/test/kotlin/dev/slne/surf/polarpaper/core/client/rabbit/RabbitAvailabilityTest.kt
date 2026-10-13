package dev.slne.surf.polarpaper.core.client.rabbit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RabbitAvailabilityTest {
    @Test
    fun `the rabbitmq client is detected on the classpath`() {
        assertTrue(isRabbitMqAvailable())
    }

    @Test
    fun `a missing rabbitmq client is detected`() {
        assertFalse(isRabbitMqAvailable(className = "dev.slne.surf.rabbitmq.api.Missing"))
    }
}
