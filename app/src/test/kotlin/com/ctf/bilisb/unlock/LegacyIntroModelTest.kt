package com.ctf.bilisb.unlock

import com.bilibili.ship.theseus.ogv.intro.floatlayer.bean.OGVIntroInfoVo
import org.junit.Assert.*
import org.junit.Test

class LegacyIntroModelTest {
    @Test fun uppercaseModelAndReorderedPublishConstructorUseCorrectSemanticValues() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> {
                if (name.startsWith("com.bilibili.ship.theseus.ogv.intro.floatlayer.") && !name.contains(".bean.")) {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name)
            }
        }
        val detail = ThSeasonDetail(100, "Title", "cover", "Description", 12, emptyList(),
            styles = "原创 科幻", areas = "中国大陆", publishDateShow = "2026/01/01", timeLengthShow = "已完结")
        val model = ViewTabHook.buildOgvIntroInfoVo(loader, detail) as OGVIntroInfoVo
        assertEquals(100L, model.seasonId)
        assertEquals("Title", model.mediaTitle)
        assertEquals("中国大陆", model.areas.single().name)
        assertEquals(listOf("原创", "科幻"), model.styles.map { it.name })
        assertEquals("2026/01/01", model.publish.releaseDateShow)
        assertEquals("已完结", model.publish.timeLengthShow)
        assertTrue(model.publish.isStarted)
        assertTrue(model.publish.isFinish)
        assertEquals("Description", model.evaluate)
    }
}
