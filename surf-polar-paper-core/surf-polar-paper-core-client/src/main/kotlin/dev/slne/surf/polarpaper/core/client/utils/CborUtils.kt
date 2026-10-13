@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.polarpaper.core.client.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

object CborUtils {
    val cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    inline fun <reified T> encode(value: T) = cbor.encodeToByteArray(value)
    inline fun <reified T> decode(bytes: ByteArray): T = cbor.decodeFromByteArray(bytes)
}
