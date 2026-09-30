package com.ctf.bilisb.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UposReplacer] 的回归（解锁 U5）：CDN host 替换的匹配/跳过/重写规则。
 */
class UposReplacerTest {

    @Test
    fun `B站 CDN 家族识别`() {
        assertTrue(UposReplacer.isUposUrl("https://upos-sz-mirrorakam.akamaized.net/upos/xx.m4s"))
        assertTrue(UposReplacer.isUposUrl("https://cn-gotcha01.bilivideo.com/v.m4s"))
        assertTrue(UposReplacer.isUposUrl("https://xy111x228x43x129xy.mcdn.bilivideo.com:4483/v.m4s"))
        assertFalse(UposReplacer.isUposUrl("http://192.168.6.179:18787/media/sample.mp4"))
    }

    @Test
    fun `PCDN 形态识别 不可简单替换 host`() {
        assertTrue(UposReplacer.isPcdnUrl("https://mcdn.bilivideo.com:4483/v.m4s"))
        assertTrue(UposReplacer.isPcdnUrl("https://xy111x228x43x129xy.mcdn.bilivideo.com:4483/v.m4s"))
        assertTrue(UposReplacer.isPcdnUrl("https://192.168.1.5:4483/v.m4s"))
        assertTrue(UposReplacer.isPcdnUrl("https://szbdyd.com/v.m4s"))
        assertFalse(UposReplacer.isPcdnUrl("https://upos-sz-mirrorakam.akamaized.net/v.m4s"))
    }

    @Test
    fun `replaceHost 只换 host 保留路径与参数`() {
        assertEquals(
            "https://mirror.example.com/upos/xx.m4s?xyz=1",
            UposReplacer.replaceHost("https://upos-sz-mirrorakam.akamaized.net/upos/xx.m4s?xyz=1", "mirror.example.com"),
        )
    }

    @Test
    fun `applyTo 重写 upos 双轨 并保留非 CDN 与 PCDN 轨`() {
        val data = PlayurlData(
            quality = 80,
            format = "dash",
            timelength = 30000L,
            videoCodecid = 12,
            videos = listOf(
                DashTrack(80, "https://upos-sz-mirrorakam.akamaized.net/v-80.m4s", listOf("https://cn-gotcha01.bilivideo.com/bk-80.m4s"), 1, 12, "", 0),
                DashTrack(64, "https://192.168.1.5:4483/v-64.m4s", emptyList(), 1, 12, "", 0),
            ),
            audios = listOf(
                DashTrack(30280, "https://xy.mcdn.bilivideo.com:4483/a.m4s", emptyList(), 1, 0, "", 0),
            ),
        )
        val out = UposReplacer.applyTo(data, "mirror.example.com")

        // upos 轨：host 替换 + backup 同步
        assertEquals("https://mirror.example.com/v-80.m4s", out.videos[0].baseUrl)
        assertEquals("https://mirror.example.com/bk-80.m4s", out.videos[0].backupUrls[0])
        // PCDN 轨：原样保留
        assertEquals("https://192.168.1.5:4483/v-64.m4s", out.videos[1].baseUrl)
        assertEquals("https://xy.mcdn.bilivideo.com:4483/a.m4s", out.audios[0].baseUrl)
        // 其余字段不动
        assertEquals(80, out.videos[0].id)
    }

    @Test
    fun `目标 host 为空时不做替换`() {
        val data = PlayurlData(
            quality = 80, format = "dash", timelength = 1L, videoCodecid = 12,
            videos = listOf(DashTrack(80, "https://upos-sz-mirrorakam.akamaized.net/v.m4s", emptyList(), 1, 12, "", 0)),
            audios = emptyList(),
        )
        assertEquals(data, UposReplacer.applyTo(data, ""))
    }
}
