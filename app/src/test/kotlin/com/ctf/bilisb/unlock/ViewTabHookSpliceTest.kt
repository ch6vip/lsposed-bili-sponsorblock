package com.ctf.bilisb.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ViewTabHook 注入器的 wire 级回归——输入为 2026-10-04 01:08 实拍的**受限形态**
 * 輝夜姬 view 响应（tab 无正季选集区块），走 hook 同款拼接链，验证兜底注入产物结构。
 */
class ViewTabHookSpliceTest {

    private val restricted: ByteArray =
        javaClass.classLoader!!.getResourceAsStream("unlock/view_reply_restricted_kaguya.bin")!!.readBytes()

    private val episodes = listOf(
        SeasonEpisode(318304L, "", 0, 1420000L, 2, "http://mock/c1.jpg", 497715774L, "1", "早坂愛想要防止", 177081266L, 1),
        SeasonEpisode(318305L, "会员", 1, 1400000L, 2, "http://mock/c2.jpg", 497715775L, "2", "輝夜姬想要問", 177081267L, 2),
    )

    @Test
    fun `受限响应的 tab 确实缺选集区块`() {
        val tab = WireSplice.firstMessage(restricted, 5)!!
        assertFalse("tab 不应含 Module.type=13 的选集区块", hasPanel(tab))
    }

    @Test
    fun `注入后 tab 携带正季 SectionData 且未知字段零损`() {
        val tab = WireSplice.firstMessage(restricted, 5)!!
        val moduleBytes = buildSectionModule(33088, episodes)
        val newTab = append(tab, moduleBytes)

        // 新 tab 里能找到 type=13 的 SectionData，且剧集字段语义正确
        val modules = flattenModules(newTab)
        val sel = modules.first { m -> firstVarint(m) == 13L }
        val sd = WireSplice.firstMessage(sel, 12)!!
        assertEquals("选集", WireSplice.parse(sd).first { it.field == 3 }.payload().toString(Charsets.UTF_8))
        val eps = WireSplice.allMessages(sd, 7)
        assertEquals(2, eps.size)
        val ep0 = WireSplice.parse(eps[0])
        assertEquals(318304L, ep0.first { it.field == 1 }.varint())
        assertEquals(177081266L, ep0.first { it.field == 14 }.varint())
        assertEquals("第1话 早坂愛想要防止", ep0.first { it.field == 44 }.payload().toString(Charsets.UTF_8))

        // 除新增区块外其余字节零损：原 tab 的元素计数 +1（新增的 modules 条目）
        val before = WireSplice.parse(tab).size
        val after = WireSplice.parse(newTab).size
        assertEquals(before, after)
    }

    @Test
    fun `reply 层 tab 替换后其余字段零损`() {
        val replyBytes = restricted
        val tab = WireSplice.firstMessage(replyBytes, 5)!!
        val newTab = append(tab, buildSectionModule(33088, episodes))
        val newReply = WireSplice.transformMessage(replyBytes, 5) { newTab }

        assertEquals("只有 tab 变化", replyBytes.size - tab.size + newTab.size, newReply.size)
        assertEquals(
            "supplement 原样保留",
            WireSplice.firstMessage(replyBytes, 6)!!.toList(),
            WireSplice.firstMessage(newReply, 6)!!.toList(),
        )
    }

    // ---- 与 ViewTabHook 相同的拼接头（私有逻辑的镜像，改动需双向同步）----

    private fun hasPanel(tab: ByteArray): Boolean {
        for (tm in WireSplice.allMessages(tab, 1)) {
            val tabType = WireSplice.parse(tm).firstOrNull()
                ?.takeIf { it.field == 1 && it.wireType == 0 }?.varint()
            if (tabType != 1L) continue
            val intro = WireSplice.firstMessage(tm, 2) ?: continue
            if (WireSplice.hasMessageWithFirstVarint(intro, 2, 13L)) return true
        }
        return false
    }

    private fun buildSectionModule(seasonId: Int, episodes: List<SeasonEpisode>): ByteArray {
        val sd = WireWriter()
        sd.int32Field(1, seasonId)
        sd.int32Field(2, seasonId)
        sd.stringField(3, "选集")
        sd.messageField(9, byteArrayOf(0x08, 0x01))
        for (ep in episodes) sd.messageField(7, UnlockWire.buildViewEpisodeBytes(ep))
        val m = WireWriter()
        m.int32Field(1, 13)
        m.messageField(12, sd.toByteArray())
        return m.toByteArray()
    }

    private fun append(tab: ByteArray, moduleBytes: ByteArray): ByteArray {
        val newTms = mutableListOf<ByteArray>()
        var touched = false
        for (tm in WireSplice.allMessages(tab, 1)) {
            val tabType = WireSplice.parse(tm).firstOrNull()
                ?.takeIf { it.field == 1 && it.wireType == 0 }?.varint()
            if (tabType == 1L && !touched) {
                touched = true
                val intro = WireSplice.firstMessage(tm, 2)
                val newIntro = WireSplice.emit(
                    WireSplice.parse(intro!!) + WireSplice.Elem(2, 2, WireSplice.message(2, moduleBytes)),
                )
                newTms += WireSplice.transformMessage(tm, 2) { newIntro }
            } else {
                newTms += tm
            }
        }
        return WireSplice.emit(
            WireSplice.parse(tab).map { e ->
                if (e.field == 1 && e.wireType == 2 && newTms.isNotEmpty()) {
                    WireSplice.Elem(1, 2, WireSplice.message(1, newTms.removeAt(0)))
                } else {
                    e
                }
            },
        )
    }

    private fun flattenModules(tab: ByteArray): List<ByteArray> =
        WireSplice.allMessages(tab, 1)
            .filter { WireSplice.parse(it).firstOrNull()?.varint() == 1L }
            .flatMap { WireSplice.allMessages(WireSplice.firstMessage(it, 2)!!, 2) }

    private fun firstVarint(module: ByteArray): Long =
        WireSplice.parse(module).first().varint()
}
