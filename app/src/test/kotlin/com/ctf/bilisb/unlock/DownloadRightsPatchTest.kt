package com.ctf.bilisb.unlock

import org.junit.Assert.*
import org.junit.Test

class DownloadRightsPatchTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream("unlock/$name")!!.readBytes()
    private fun message(bytes: ByteArray, vararg fields: Int): ByteArray =
        fields.fold(bytes) { data, field -> WireSplice.firstMessage(data, field)!! }
    private fun value(bytes: ByteArray, field: Int): Long =
        WireSplice.parse(bytes).firstOrNull { it.field == field && it.wireType == 0 }?.varint() ?: 0

    @Test fun `restricted fixture opens download rights without inventing a panel`() {
        val original = fixture("view_reply_restricted_kaguya.bin")
        val patched = DownloadRightsPatch.patch(original)
        val rights = message(patched, 6, 2, 1, 5)
        assertEquals(1L, value(rights, 1))
        assertEquals(0L, value(rights, 15))
        assertEquals(1L, value(rights, 16))
        assertArrayEquals(message(original, 5), message(patched, 5))
        // PGC metadata other than the rights submessage remains byte-for-byte intact.
        fun withoutRights(bytes: ByteArray) = WireSplice.emit(
            WireSplice.parse(message(bytes, 6, 2, 1)).filterNot { it.field == 5 },
        )
        assertArrayEquals(withoutRights(original), withoutRights(patched))
        assertArrayEquals(patched, DownloadRightsPatch.patch(patched))
    }

    @Test fun `existing real episode panels retain episodes and update their download rights`() {
        val original = fixture("view_reply_mdb.bin")
        val patched = DownloadRightsPatch.patch(original)
        fun episodes(bytes: ByteArray): List<ByteArray> = WireSplice.allMessages(message(bytes, 5), 1)
            .filter { value(it, 1) == 1L }
            .flatMap { WireSplice.allMessages(message(it, 2), 2) }
            .filter { value(it, 1) == 13L }
            .flatMap { WireSplice.allMessages(message(it, 12), 7) }
        val before = episodes(original)
        val after = episodes(patched)
        assertTrue(before.isNotEmpty())
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (old, new) ->
            assertEquals(1L, value(message(new, 22), 1))
            assertArrayEquals(
                WireSplice.emit(WireSplice.parse(old).filterNot { it.field == 22 }),
                WireSplice.emit(WireSplice.parse(new).filterNot { it.field == 22 }),
            )
        }
    }

    @Test fun `non PGC replies are unchanged`() {
        val bytes = WireWriter().apply { stringField(1, "unrelated") }.toByteArray()
        assertSame(bytes, DownloadRightsPatch.patch(bytes))
    }
}
