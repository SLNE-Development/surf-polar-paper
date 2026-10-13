package dev.slne.surf.polarpaper.core.client.rabbit

private const val RABBITMQ_CLIENT_CLASS = "dev.slne.surf.rabbitmq.api.ClientRabbitMQApi"

private object ClassLoaderAnchor

/**
 * Whether the RabbitMQ client is on the classpath. The platform provides it through a separate, optional plugin.
 *
 * Kept apart from [RabbitServices] so checking it never loads any RabbitMQ class.
 */
fun isRabbitMqAvailable(
    className: String = RABBITMQ_CLIENT_CLASS,
    classLoader: ClassLoader? = ClassLoaderAnchor::class.java.classLoader
) = try {
    Class.forName(className, false, classLoader)
    true
} catch (_: ClassNotFoundException) {
    false
}
