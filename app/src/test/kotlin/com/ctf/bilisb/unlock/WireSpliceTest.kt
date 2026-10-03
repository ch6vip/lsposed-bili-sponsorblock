package com.ctf.bilisb.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WireSplice 对宿主实拍字节的解析回归（2026-10-04 viewtab_reply_1.bin）。 */
class WireSpliceTest {

    private fun res(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("unlock/$name")!!.readBytes()

    @Test
    fun `实拍 view reply 可完整解析且往返零损`() {
        val bytes = res("viewtab_reply_1.bin")
        val elems = WireSplice.parse(bytes)
        assertTrue("顶层应含 tab(field5)", elems.any { it.field == 5 && it.wireType == 2 })
        assertEquals("往返零损", bytes.toList(), WireSplice.emit(elems).toList())
    }

    @Test
    fun `迷宫饭 reply 的 tab 层级可走通`() {
        val bytes = res("view_reply_mdb.bin")
        val tab = WireSplice.firstMessage(bytes, 5)!!
        if (tab.size < 8 || tab[0] != 0x0a.toByte()) {
            throw IllegalStateException(
                "tab 异常: size=" + tab.size +
                    " head=" + tab.take(16).joinToString(" ") { "%02x".format(it) },
            )
        }
        val tms = WireSplice.allMessages(tab, 1)
        assertTrue(tms.isNotEmpty())
        val err = try {
            val intro = WireSplice.firstMessage(tms[0], 2)!!
            assertTrue("简介页 modules 非空", WireSplice.allMessages(intro, 2).isNotEmpty())
            null
        } catch (t: Throwable) {
            throw IllegalStateException(
                "tm size=" + tms[0].size +
                    " head=" + tms[0].take(24).joinToString(" ") { "%02x".format(it) } +
                    " cause=" + t.message,
            )
        }
        if (err != null) throw IllegalStateException("nested parse failed: " + err)
    }
}
