package com.ctf.bilisb.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTabHookTest {

    class MockTab {
        var tabId: String = ""
        var name: String = ""
        var uri: String = ""
        var reportId: String = ""
        var pos: Int = 0
    }

    class MockTabData {
        var tab: List<MockTab> = emptyList()
    }

    class MockTabResponse {
        var tabData: MockTabData = MockTabData()
    }

    @Test
    fun `null tabResponse returns false`() {
        assertFalse(HomeTabHook.injectBangumiTabs(null, MockTab::class.java))
    }

    @Test
    fun `injects mainland and hk-tw bangumi tabs when missing`() {
        val resp = MockTabResponse()
        val originalTab = MockTab().apply {
            tabId = "1"
            name = "推荐"
            uri = "bilibili://pegasus/promo"
            pos = 1
        }
        resp.tabData.tab = listOf(originalTab)

        val changed = HomeTabHook.injectBangumiTabs(resp, MockTab::class.java)
        assertTrue(changed)

        val tabs = resp.tabData.tab
        assertEquals(3, tabs.size)
        assertEquals("推荐", tabs[0].name)

        val mainland = tabs.first { it.uri == "bilibili://pgc/home" }
        assertEquals("50", mainland.tabId)
        assertEquals("追番（大陆）", mainland.name)
        assertEquals("bangumi", mainland.reportId)
        assertEquals(50, mainland.pos)

        val hkMoTw = tabs.first { it.uri == "bilibili://following/home_activity_tab/6544" }
        assertEquals("60", hkMoTw.tabId)
        assertEquals("追番（港澳台）", hkMoTw.name)
        assertEquals("bangumi", hkMoTw.reportId)
        assertEquals(60, hkMoTw.pos)
    }

    @Test
    fun `idempotent when both tabs already exist`() {
        val resp = MockTabResponse()
        resp.tabData.tab = listOf(
            MockTab().apply { uri = "bilibili://pgc/home" },
            MockTab().apply { uri = "bilibili://following/home_activity_tab/6544" },
        )

        val changed = HomeTabHook.injectBangumiTabs(resp, MockTab::class.java)
        assertFalse(changed)
        assertEquals(2, resp.tabData.tab.size)
    }

    @Test
    fun `injects only missing tab when one already exists`() {
        val resp = MockTabResponse()
        resp.tabData.tab = listOf(
            MockTab().apply {
                name = "追番"
                uri = "bilibili://pgc/bangumi_v2"
            }
        )

        val changed = HomeTabHook.injectBangumiTabs(resp, MockTab::class.java)
        assertTrue(changed)

        val tabs = resp.tabData.tab
        assertEquals(2, tabs.size)
        assertTrue(tabs.any { it.uri == "bilibili://following/home_activity_tab/6544" })
        assertEquals(1, tabs.count { it.uri == "bilibili://pgc/bangumi_v2" })
    }

    @Test
    fun `idempotent when identified by tabId only`() {
        val resp = MockTabResponse()
        resp.tabData.tab = listOf(
            MockTab().apply { tabId = "50"; uri = "custom://uri1" },
            MockTab().apply { tabId = "60"; uri = "custom://uri2" },
        )

        val changed = HomeTabHook.injectBangumiTabs(resp, MockTab::class.java)
        assertFalse(changed)
        assertEquals(2, resp.tabData.tab.size)
    }

    class MockEmptyResp

    @Test
    fun `handles missing or null tabData gracefully`() {
        assertFalse(HomeTabHook.injectBangumiTabs(MockEmptyResp(), MockTab::class.java))
    }

    class BeanTab {
        private var tabId: String = ""
        private var name: String = ""
        private var uri: String = ""
        private var reportId: String = ""
        private var pos: Int = 0

        fun getTabId(): String = tabId
        fun setTabId(v: String) { tabId = v }
        fun getName(): String = name
        fun setName(v: String) { name = v }
        fun getUri(): String = uri
        fun setUri(v: String) { uri = v }
        fun getReportId(): String = reportId
        fun setReportId(v: String) { reportId = v }
        fun getPos(): Int = pos
        fun setPos(v: Int) { pos = v }
    }

    class BeanResponse {
        var tabData: MockTabData = MockTabData()
    }

    @Test
    fun `injects tabs using getter and setter methods fallback`() {
        val resp = BeanResponse()
        val changed = HomeTabHook.injectBangumiTabs(resp, BeanTab::class.java)
        assertTrue(changed)
        assertEquals(2, resp.tabData.tab.size)
    }

    // --- 6.6.0 混淆数据模型测试 ---
    class MockK660 {
        var a: String = "" // tabId / id
        var b: String = "" // name
        var c: String = "" // uri
        var f: Int = 0    // default_selected
        var g: Int = 0    // pos
        var h: String = "" // reportId / tab_id
        var p: Long = 0L   // expired_at
    }

    class MockJ660 {
        var b: List<MockK660> = emptyList() // tab
    }

    class MockL660 {
        var d: MockJ660 = MockJ660() // data
    }

    class MockAction660 {
        var b: MockJ660 = MockJ660() // action payload holding HomeTabData
    }

    @Test
    fun `injects 660 HomeTabResponse fE1 l model successfully`() {
        val resp = MockL660()
        val live = MockK660().apply { a = "4016"; b = "直播"; c = "bilibili://live/home"; g = 1 }
        val promo = MockK660().apply { a = "152"; b = "推荐"; c = "bilibili://pegasus/promo"; g = 2; f = 1 }
        val hot = MockK660().apply { a = "4042"; b = "热门"; c = "bilibili://pegasus/hottopic"; g = 3 }
        resp.d.b = listOf(live, promo, hot)

        val changed = HomeTabHook.injectBangumiTabs(resp)
        assertTrue(changed)

        val list = resp.d.b
        assertEquals(5, list.size)
        assertEquals("直播", list[0].b)
        assertEquals("推荐", list[1].b)
        assertEquals("热门", list[2].b)

        val mainland = list.first { it.c == "bilibili://pgc/home" }
        assertEquals("50", mainland.a)
        assertEquals("追番（大陆）", mainland.b)
        assertEquals("bangumi", mainland.h)
        assertEquals(50, mainland.g)
        assertEquals(0, mainland.f)
        assertEquals(0L, mainland.p)

        val hkMoTw = list.first { it.c == "bilibili://following/home_activity_tab/6544" }
        assertEquals("60", hkMoTw.a)
        assertEquals("追番（港澳台）", hkMoTw.b)
        assertEquals("bangumi", hkMoTw.h)
        assertEquals(60, hkMoTw.g)
        assertEquals(0, hkMoTw.f)
        assertEquals(0L, hkMoTw.p)
    }

    @Test
    fun `injects 660 HomeFrameViewModel Action payload holding fE1 j model`() {
        val action = MockAction660()
        val promo = MockK660().apply { a = "152"; b = "推荐"; c = "bilibili://pegasus/promo"; g = 2 }
        action.b.b = listOf(promo)

        val changed = HomeTabHook.injectBangumiTabs(action)
        assertTrue(changed)
        assertEquals(3, action.b.b.size)
        assertTrue(action.b.b.any { it.b == "追番（大陆）" })
        assertTrue(action.b.b.any { it.b == "追番（港澳台）" })
    }
}

