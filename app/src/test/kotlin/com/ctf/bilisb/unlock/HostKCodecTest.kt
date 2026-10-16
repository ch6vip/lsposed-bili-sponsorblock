package com.ctf.bilisb.unlock

import org.junit.Assert.*
import org.junit.Test

class HostKCodecTest {
    data class HostValue(val bytes: ByteArray)
    class HostProto {
        var failEncoding = false
        var failDecoding = false
        fun encodeToByteArray(serializer: Any, value: Any): ByteArray {
            check(!failEncoding)
            check(serializer === Serializer)
            return (value as HostValue).bytes
        }
        fun decodeFromByteArray(serializer: Any, bytes: ByteArray): Any {
            check(!failDecoding)
            check(serializer === Serializer)
            return HostValue(bytes)
        }
    }
    object Serializer

    @Test fun replacementIsDecodedIntoHostTypeAndPreservesUnknownWireFields() {
        val original = HostValue(byteArrayOf(8, 1, 120, 7))
        val codec = HostKCodec(HostProto())
        val mapped = codec.transform(original, Serializer, Serializer) { bytes ->
            WireSplice.emit(WireSplice.parse(bytes).map { elem ->
                if (elem.field == 1) WireSplice.parse(WireSplice.varintElem(1, 2)).single() else elem
            })
        } as HostValue
        assertArrayEquals(byteArrayOf(8, 2, 120, 7), mapped.bytes)
        assertArrayEquals(byteArrayOf(8, 1, 120, 7), original.bytes)
    }
    @Test fun everyConversionFailureReturnsExactOriginalInstance() {
        val proto = HostProto()
        val codec = HostKCodec(proto)
        val original = HostValue(byteArrayOf(8, 1))
        assertSame(original, codec.transform(original, Serializer, Serializer) { null })
        assertSame(original, codec.transform(original, Serializer, Serializer) { error("bad response") })
        proto.failEncoding = true
        assertSame(original, codec.transform(original, Serializer, Serializer) { it })
        proto.failEncoding = false
        proto.failDecoding = true
        assertSame(original, codec.transform(original, Serializer, Serializer) { it })
    }
}
