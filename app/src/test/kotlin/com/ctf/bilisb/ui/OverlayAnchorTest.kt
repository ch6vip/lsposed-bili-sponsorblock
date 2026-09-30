package com.ctf.bilisb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OverlayAnchor.isUsableAnchor] 的判定测试。
 *
 * 这段判定决定「手动跳过按钮 / 倒计时浮层挂哪」：挂到播放器容器上才能跟随播放器 bounds
 * （详情页滚动、小窗场景位置正确），回落 decorView 就退回「整屏右下角」的老行为。
 * 真机上表现只是「位置不对/不出现」，所以判定条件必须在这里钉死。
 */
class OverlayAnchorTest {

    private val decorW = 1080
    private val decorH = 2400

    /** 正常播放页：播放器占满宽度、高度约 2/3，父容器就是播放器框。 */
    private fun usable(
        playerW: Int = 1080,
        playerH: Int = 1600,
        anchorW: Int = 1080,
        anchorH: Int = 1600,
        anchorClips: Boolean = false,
        playerAttached: Boolean = true,
        anchorAttached: Boolean = true,
    ): String? = OverlayAnchor.isUsableAnchor(
        playerWidth = playerW,
        playerHeight = playerH,
        decorWidth = decorW,
        decorHeight = decorH,
        anchorWidth = anchorW,
        anchorHeight = anchorH,
        anchorClipsChildren = anchorClips,
        playerAttached = playerAttached,
        anchorAttached = anchorAttached,
    )

    @Test
    fun `正常的播放器容器可用`() {
        assertNull("标准播放页应挂到播放器容器", usable())
    }

    @Test
    fun `decorView 未测量时不可用`() {
        val reason = OverlayAnchor.isUsableAnchor(
            playerWidth = 1080, playerHeight = 1600,
            decorWidth = 0, decorHeight = 0,
            anchorWidth = 1080, anchorHeight = 1600,
            anchorClipsChildren = false,
            playerAttached = true, anchorAttached = true,
        )
        assertEquals("decorNotMeasured", reason)
    }

    @Test
    fun `播放器未 attach 时回落`() {
        assertEquals("playerNotAttached", usable(playerAttached = false))
    }

    @Test
    fun `容器未 attach 时回落`() {
        assertEquals("anchorNotAttached", usable(anchorAttached = false))
    }

    @Test
    fun `播放器过小时回落（避免被当成装饰性小 View）`() {
        // 高度 2400 * 0.25 = 600；480 不够高
        val reason = usable(playerH = 480)
        assertTrue("应因播放器过小回落，实际=$reason", reason!!.startsWith("playerTooSmall"))
    }

    /** 父容器覆盖全屏时「跟随播放器」没有意义，回落 decorView。 */
    @Test
    fun `挂载点接近整屏时回落`() {
        // 两者都 ≥ 0.9 才算覆盖全屏
        val reason = usable(anchorW = 1080, anchorH = 2400)
        assertTrue("应因挂载点覆盖全屏回落，实际=$reason", reason!!.startsWith("anchorCoversScreen"))
    }

    /** 只有一边接近整屏（例如全宽但高度只有一半）仍算有效挂载点。 */
    @Test
    fun `仅宽度接近整屏不算覆盖全屏`() {
        assertNull(usable(anchorW = 1080, anchorH = 1200))
    }

    /** 宿主用 clipChildren 裁子 View 时浮层会被裁没，宁可回落 decorView（至少可见）。 */
    @Test
    fun `父容器裁剪子 View 时回落`() {
        assertEquals("anchorClipsChildren", usable(anchorClips = true))
    }

    @Test
    fun `容器未测量时回落`() {
        assertEquals("anchorNotMeasured", usable(anchorW = 0, anchorH = 0))
    }

    @Test
    fun `播放器未测量时回落`() {
        assertEquals("playerNotMeasured", usable(playerW = 0, playerH = 0))
    }

    @Test
    fun `阈值常量与判定边界一致`() {
        assertEquals(0.25f, OverlayAnchor.MIN_PLAYER_FRACTION)
        assertEquals(0.90f, OverlayAnchor.MAX_ANCHOR_FRACTION)
        // 恰好等于下限：不算过小（>= 阈值）
        val atMin = (decorH * OverlayAnchor.MIN_PLAYER_FRACTION).toInt()
        assertNull(usable(playerH = atMin, anchorH = atMin))
    }
}
