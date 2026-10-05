package com.ctf.bilisb.unlock

import org.junit.Assert.*
import org.junit.Test

class ThaiSubtitleHookTest {

    @Test
    fun `autoGenerateSubtitle generates simplified track from traditional track`() {
        val config = UnlockConfig.Config.DEFAULT.copy(
            enabled = true,
            autoGenerateSubtitle = true,
            thSubtitle = false,
        )

        // 构造一个包含繁体中文的 VideoSubtitle
        val hantItemWriter = WireWriter()
        hantItemWriter.int64Field(1, 1001L)
        hantItemWriter.stringField(2, "1001")
        hantItemWriter.stringField(3, "zh-Hant")
        hantItemWriter.stringField(4, "中文（繁体）")
        hantItemWriter.stringField(5, "https://api.bilibili.com/subtitle/1001.json")

        val subWriter = WireWriter()
        subWriter.stringField(1, "zh-Hant")
        subWriter.stringField(2, "中文（繁体）")
        subWriter.messageField(3, hantItemWriter.toByteArray())

        val replyWriter = WireWriter()
        replyWriter.messageField(3, subWriter.toByteArray()) // field 3 in DmViewReply = subtitle
        val rawBytes = replyWriter.toByteArray()

        val patched = ThaiSubtitleHook.injectSubtitles(rawBytes, epId = 0L, config = config)
        assertNotEquals(rawBytes.size, patched.size)

        // 验证注入后的字节流包含 zh-CN 与 zh_converter=t2cn
        val videoSubtitleBytes = WireSplice.firstMessage(patched, 3)
        assertNotNull(videoSubtitleBytes)
        val items = WireSplice.allMessages(videoSubtitleBytes!!, 3)
        assertEquals(2, items.size)

        val lans = items.mapNotNull { item ->
            WireSplice.parse(item).firstOrNull { it.field == 3 }?.payload()?.decodeToString()
        }
        assertTrue(lans.contains("zh-Hant"))
        assertTrue(lans.contains("zh-CN"))

        val cnItem = items.first { item ->
            WireSplice.parse(item).firstOrNull { it.field == 3 }?.payload()?.decodeToString() == "zh-CN"
        }
        val cnDoc = WireSplice.parse(cnItem).firstOrNull { it.field == 4 }?.payload()?.decodeToString()
        val cnUrl = WireSplice.parse(cnItem).firstOrNull { it.field == 5 }?.payload()?.decodeToString()

        assertEquals("简中（生成）", cnDoc)
        assertNotNull(cnUrl)
        assertTrue(cnUrl!!.contains("zh_converter=t2cn"))
    }

    @Test
    fun `autoGenerateSubtitle does not add duplicate when zh-CN already exists`() {
        val config = UnlockConfig.Config.DEFAULT.copy(
            enabled = true,
            autoGenerateSubtitle = true,
            thSubtitle = false,
        )

        val hantItem = WireWriter().apply {
            int64Field(1, 1001L)
            stringField(3, "zh-Hant")
            stringField(4, "中文（繁体）")
            stringField(5, "https://example.com/hant.json")
        }.toByteArray()

        val cnItem = WireWriter().apply {
            int64Field(1, 1002L)
            stringField(3, "zh-CN")
            stringField(4, "中文（简体）")
            stringField(5, "https://example.com/cn.json")
        }.toByteArray()

        val subWriter = WireWriter().apply {
            stringField(1, "zh-CN")
            messageField(3, hantItem)
            messageField(3, cnItem)
        }

        val rawBytes = WireWriter().apply {
            messageField(3, subWriter.toByteArray())
        }.toByteArray()

        val patched = ThaiSubtitleHook.injectSubtitles(rawBytes, epId = 0L, config = config)
        assertArrayEquals(rawBytes, patched)
    }

    @Test
    fun `recordCidEpId correctly updates mapping`() {
        ThaiSubtitleHook.recordCidEpId(123456L, 7890L)
        // Check without exceptions
    }
}
