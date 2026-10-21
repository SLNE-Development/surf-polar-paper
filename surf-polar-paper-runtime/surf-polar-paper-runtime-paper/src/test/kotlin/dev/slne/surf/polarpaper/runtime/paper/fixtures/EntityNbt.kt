package dev.slne.surf.polarpaper.runtime.paper.fixtures

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Entity NBT bytes the way Polar's entity serializer writes them. */
fun entityNbt(id: String): ByteArray {
    val tag = CompoundTag().apply { putString("id", id) }
    val out = ByteArrayOutputStream()
    NbtIo.write(tag, DataOutputStream(out))
    return out.toByteArray()
}
