package com.ctf.bilisb.unlock

/** Use host serializers so K objects never cross into the protobuf-Java response pipeline. */
internal class HostKCodec(private val protoBuf: Any) {
    fun encode(serializer: Any, value: Any): ByteArray = protoBuf.javaClass.methods.first {
        it.name == "encodeToByteArray" && it.parameterTypes.size == 2
    }.invoke(protoBuf, serializer, value) as ByteArray

    fun decode(deserializer: Any, bytes: ByteArray): Any = protoBuf.javaClass.methods.first {
        it.name == "decodeFromByteArray" && it.parameterTypes.size == 2
    }.invoke(protoBuf, deserializer, bytes)

    fun transform(value: Any, serializer: Any, deserializer: Any, operation: (ByteArray) -> ByteArray?): Any =
        runCatching {
            val bytes = encode(serializer, value)
            val changed = operation(bytes) ?: return value
            decode(deserializer, changed)
        }.getOrDefault(value)
}
