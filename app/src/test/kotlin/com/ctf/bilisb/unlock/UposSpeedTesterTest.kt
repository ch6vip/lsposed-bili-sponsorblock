package com.ctf.bilisb.unlock

import org.junit.Assert.*
import org.junit.Test

class UposSpeedTesterTest {

    @Test
    fun `NODES contains exactly 20 pre-configured upos mirrors`() {
        assertEquals(20, UposSpeedTester.NODES.size)
        assertTrue(UposSpeedTester.NODES.any { it.host.contains("mirrorali") })
        assertTrue(UposSpeedTester.NODES.any { it.host.contains("mirrorcos") })
        assertTrue(UposSpeedTester.NODES.any { it.host.contains("mirrorhw") })
        assertTrue(UposSpeedTester.NODES.any { it.host.contains("akamaized") })
    }

    @Test
    fun `formatSpeed formats bytes per second appropriately`() {
        assertEquals("0 KB/s", UposSpeedTester.formatSpeed(0L))
        assertEquals("0 KB/s", UposSpeedTester.formatSpeed(-100L))
        assertEquals("512 KB/s", UposSpeedTester.formatSpeed(512 * 1024L))
        assertEquals("1.5 MB/s", UposSpeedTester.formatSpeed((1.5 * 1024 * 1024).toLong()))
        assertEquals("10.0 MB/s", UposSpeedTester.formatSpeed(10 * 1024 * 1024L))
    }

    @Test
    fun `rewriteMediaUrl replaces host and adds bandwidth param`() {
        val origUrl = "https://upos-sz-mirror08c.bilivideo.com/upgcxcode/123.mp4?deadline=17000000"
        val targetHost = "upos-sz-mirrorali.bilivideo.com"

        val rewritten = IjkPlayerUposHook.rewriteMediaUrl(origUrl, targetHost)
        assertTrue(rewritten.startsWith("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/123.mp4"))
        assertTrue(rewritten.contains("bw=1280000"))
        assertTrue(rewritten.contains("deadline=17000000"))

        // 原已有 bw 参数时覆盖为 1280000
        val withBw = "https://example.com/test.mp4?bw=50000&deadline=123"
        val rewrittenBw = IjkPlayerUposHook.rewriteMediaUrl(withBw, targetHost)
        assertTrue(rewrittenBw.contains("bw=1280000"))
        assertFalse(rewrittenBw.contains("bw=50000"))
    }
}
