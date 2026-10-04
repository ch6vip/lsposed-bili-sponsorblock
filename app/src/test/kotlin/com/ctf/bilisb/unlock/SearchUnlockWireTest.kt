package com.ctf.bilisb.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索解锁 wire 回归（S 线 S2/S3）：nav 注入幂等 + 服务端 JSON→SearchByTypeResponse 重建。
 * 字段号依据 runtime *_FIELD_NUMBER dump 与实拍模板解析（SEARCH_UNLOCK_PLAN.md §4）。
 */
class SearchUnlockWireTest {

    // ---------------------------------------------------------------- S2 nav 注入

    private fun searchAllBytes(navTypes: List<Int>): ByteArray {
        val w = WireWriter()
        w.stringField(1, "kw")
        for (t in navTypes) w.messageField(3, nativeNav("页签$t", t))
        w.messageField(4, WireSplice.message(4, ByteArray(4)))
        return w.toByteArray()
    }

    /** 原生 Nav 形态（实拍：番剧={1:'番剧',2:2(total),3:1(pages),4:7(type)}）。 */
    private fun nativeNav(name: String, type: Int): ByteArray {
        val w = WireWriter()
        w.stringField(1, name)
        w.int64Field(2, 2)
        w.int64Field(3, 1)
        w.int64Field(4, type.toLong())
        return w.toByteArray()
    }

    private fun navTypeOf(payload: ByteArray): Long =
        WireSplice.parse(payload).firstOrNull { it.field == 4 && it.wireType == 0 }?.varint() ?: -1

    @Test
    fun `S2 nav 注入插在第一个原生页签后`() {
        val src = searchAllBytes(listOf(7, 2))
        val out = SearchUnlockHook.spliceAreaNav(src, "台", 810)!!
        val navs = WireSplice.allMessages(out, 3)
        assertEquals(listOf(7L, 810L, 2L), navs.map(::navTypeOf))
        // 原生 nav 其余字段零损
        val injected = navs[1]
        val fields = WireSplice.parse(injected).map { it.field }
        // WireWriter 跳过零值（proto 默认省略）：total/pages=0 不落字节，语义等价
        assertTrue(1 in fields && 4 in fields)
        assertEquals("台", WireSplice.parse(injected)
            .firstOrNull { it.field == 1 }?.payload()?.decodeToString())
    }

    @Test
    fun `S2 nav 注入幂等 已含标记时返回 null`() {
        val withMarker = searchAllBytes(listOf(7, 810))
        assertNull(SearchUnlockHook.spliceAreaNav(withMarker, "台", 810))
    }

    @Test
    fun `S2 无 nav 的响应也能追加`() {
        val src = searchAllBytes(emptyList())
        val out = SearchUnlockHook.spliceAreaNav(src, "台", 810)!!
        val navs = WireSplice.allMessages(out, 3)
        assertEquals(listOf(810L), navs.map(::navTypeOf))
    }

    // ---------------------------------------------------------------- S3 JSON→wire

    /** 服务端卡样本（字段取自 2662 实测 /x/v2/search/type?type=7 响应，截短）。 */
    private val serverJson = """
        {"code":0,"message":"0","data":{"pages":1,"items":[
          {"goto":"bangumi","param":"28237120","season_id":41411,
           "title":"<em class=\"keyword\">辉夜大小姐</em>想让我告白 -究极浪漫-",
           "cover":"https://i0.hdslb.com/bfs/x.png","area":"日本",
           "style":"日常/搞笑/校园/恋爱/漫画改","styles":"2022 | 番剧 | 日本",
           "ptime":1651161600,"season_type_name":"番剧","rating":9.5,"vote":52472,
           "trackid":"5284502823764829869","selection_style":"grid",
           "badges_v2":[{"bg_color":"#FF6699","bg_color_night":"#D44E7D","bg_style":1,
             "text":"番剧","text_color":"#FFFFFF","text_color_night":"#FFFFFF"}],
           "episodes_new":[{"is_new":0,"param":"508407","position":1,"title":"1",
             "uri":"https://www.bilibili.com/bangumi/play/ep508407"}]},
          {"goto":"av","param":"123","title":"非番剧卡应被跳过"}
        ]}}
    """.trimIndent()

    private fun itemFields(bytes: ByteArray) = WireSplice.parse(bytes)
        .filter { it.wireType == 2 }.associate { it.field to it.payload() }

    @Test
    fun `S3 JSON 重建响应壳 trackid_pages_keyword_items`() {
        val bytes = SearchUnlockHook.buildSearchResponseBytes("輝夜姬", serverJson)
        val top = WireSplice.parse(bytes)
        assertEquals("輝夜姬", top.first { it.field == 4 }.payload().decodeToString())
        assertEquals(1L, top.first { it.field == 2 && it.wireType == 0 }.varint())
        assertEquals(1, WireSplice.allMessages(bytes, 6).size) // av 卡被跳过
    }

    @Test
    fun `S3 番剧卡字段映射与模板一致`() {
        val bytes = SearchUnlockHook.buildSearchResponseBytes("輝夜姬", serverJson)
        val item = WireSplice.allMessages(bytes, 6).first()
        val f = itemFields(item)
        assertEquals("28237120", f[2]?.decodeToString())          // param
        assertEquals("bangumi", f[3]?.decodeToString())           // goto
        assertEquals("media_bangumi", f[4]?.decodeToString())     // linktype
        assertEquals("5284502823764829869", f[6]?.decodeToString()) // trackid
        // 卡体（38）
        val cardFields = WireSplice.parse(f[38]!!)
        val cf = cardFields.filter { it.wireType == 2 }.associate { it.field to it.payload() }
        // <em> 高亮标签被剥除
        assertEquals("辉夜大小姐想让我告白 -究极浪漫-", cf[1]?.decodeToString())
        assertEquals("https://i0.hdslb.com/bfs/x.png", cf[2]?.decodeToString())
        assertEquals("2022 | 番剧 | 日本", cf[7]?.decodeToString())
        assertEquals(1651161600L, cardFields.first { it.field == 14 && it.wireType == 0 }.varint())
        assertEquals("番剧", cf[15]?.decodeToString())
        // badge（32）
        val badge = WireSplice.parse(cf[32]!!)
        assertEquals("番剧", badge.first { it.field == 1 }.payload().decodeToString())
        assertEquals("#FF6699", badge.first { it.field == 4 }.payload().decodeToString())
        // 集网格（26）
        val eps = WireSplice.allMessages(f[38]!!, 26)
        assertEquals(1, eps.size)
        val ef = WireSplice.parse(eps[0]).filter { it.wireType == 2 }
        assertEquals("1", ef.first { it.field == 1 }.payload().decodeToString())
        assertEquals("https://www.bilibili.com/bangumi/play/ep508407",
            ef.first { it.field == 2 }.payload().decodeToString())
        assertEquals("508407", ef.first { it.field == 3 }.payload().decodeToString())
    }

    @Test
    fun `S3 服务端错误码抛异常不产字节`() {
        val bad = """{"code":-3,"message":"API校验密匙错误"}"""
        val err = runCatching { SearchUnlockHook.buildSearchResponseBytes("k", bad) }
        assertTrue(err.isFailure)
        assertNotNull(err.exceptionOrNull())
    }
}
