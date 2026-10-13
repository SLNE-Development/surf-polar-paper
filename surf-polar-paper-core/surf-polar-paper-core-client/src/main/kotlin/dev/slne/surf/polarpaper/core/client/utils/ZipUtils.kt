package dev.slne.surf.polarpaper.core.client.utils

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories

object ZipUtils {
    fun zipBytes(
        bytes: ByteArray,
    ): ByteArray = ByteArrayOutputStream().use { byteArrayOutputStream ->
        ZipOutputStream(byteArrayOutputStream).use { zipOutputStream ->
            zipOutputStream.putNextEntry(ZipEntry("world.bin"))
            zipOutputStream.write(bytes)
            zipOutputStream.closeEntry()
        }

        byteArrayOutputStream.toByteArray()
    }

    fun unzipBytes(
        bytes: ByteArray,
    ): ByteArray = ZipInputStream(bytes.inputStream()).use { zipInputStream ->
        val entry = zipInputStream.nextEntry
            ?: throw IllegalArgumentException("No entries found in the zip data")

        require(!entry.isDirectory) {
            "The zip entry is a directory, expected a file"
        }

        require(entry.name == "world.bin") {
            "Unexpected zip entry name: ${entry.name}, expected 'world.bin'"
        }

        zipInputStream.readBytes()
    }

    fun zipFile(
        name: String,
        fileContent: ByteArray,
        path: Path
    ) {
        path.parent.createDirectories()

        ZipOutputStream(Files.newOutputStream(path)).use { zipOutputStream ->
            zipOutputStream.putNextEntry(ZipEntry(name))
            zipOutputStream.write(fileContent)
            zipOutputStream.closeEntry()
        }
    }

    fun unzipFile(
        zipPath: Path
    ): ByteArray? {
        if (!Files.exists(zipPath)) {
            return null
        }

        ZipInputStream(Files.newInputStream(zipPath)).use { zipInputStream ->
            if (zipInputStream.nextEntry == null) {
                return null
            }

            return zipInputStream.readBytes()
        }
    }
}