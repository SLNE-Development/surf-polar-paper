package dev.slne.surf.polarpaper.core.client.utils

import java.util.*

internal fun String.toUuidOrNull(): UUID? {
    return try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        null
    }
}
