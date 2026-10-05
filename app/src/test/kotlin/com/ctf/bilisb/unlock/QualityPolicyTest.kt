package com.ctf.bilisb.unlock

import org.junit.Assert.*
import org.junit.Test

class QualityPolicyTest {

    @Test
    fun `resolveTargetQn handles fullscreen orientation properly`() {
        assertEquals(-1, QualityPolicy.resolveTargetQn(fullScreenPref = "-1", halfScreenPref = "0", isLandscape = true))
        assertEquals(127, QualityPolicy.resolveTargetQn(fullScreenPref = "127", halfScreenPref = "0", isLandscape = true))
        assertEquals(80, QualityPolicy.resolveTargetQn(fullScreenPref = "80", halfScreenPref = "64", isLandscape = true))
        assertEquals(0, QualityPolicy.resolveTargetQn(fullScreenPref = "0", halfScreenPref = "64", isLandscape = true))
    }

    @Test
    fun `resolveTargetQn handles halfscreen orientation properly`() {
        // 跟随全屏
        assertEquals(80, QualityPolicy.resolveTargetQn(fullScreenPref = "80", halfScreenPref = "1", isLandscape = false))
        assertEquals(-1, QualityPolicy.resolveTargetQn(fullScreenPref = "-1", halfScreenPref = "1", isLandscape = false))
        assertEquals(0, QualityPolicy.resolveTargetQn(fullScreenPref = "0", halfScreenPref = "1", isLandscape = false))

        // 独立设置
        assertEquals(64, QualityPolicy.resolveTargetQn(fullScreenPref = "80", halfScreenPref = "64", isLandscape = false))
        assertEquals(-1, QualityPolicy.resolveTargetQn(fullScreenPref = "80", halfScreenPref = "-1", isLandscape = false))
        assertEquals(0, QualityPolicy.resolveTargetQn(fullScreenPref = "80", halfScreenPref = "0", isLandscape = false))
    }

    @Test
    fun `shouldPatchQuality returns true only when either pref is non-zero`() {
        assertFalse(QualityPolicy.shouldPatchQuality("0", "0"))
        assertFalse(QualityPolicy.shouldPatchQuality("", ""))
        assertTrue(QualityPolicy.shouldPatchQuality("-1", "0"))
        assertTrue(QualityPolicy.shouldPatchQuality("0", "80"))
        assertTrue(QualityPolicy.shouldPatchQuality("80", "1"))
        assertTrue(QualityPolicy.shouldPatchQuality("120", "64"))
    }

    @Test
    fun `patchReqObject injects fnval fourk and qn via reflection`() {
        class DummyReq {
            private var _fnval: Int = 0
            private var _fourk: Boolean = false
            private var _qn: Long = 0

            val fnval: Int get() = _fnval
            val fourk: Boolean get() = _fourk
            val qn: Long get() = _qn

            fun setFnval(v: Int) { _fnval = v }
            fun setFourk(v: Boolean) { _fourk = v }
            fun setQn(v: Long) { _qn = v }
        }

        val req = DummyReq()
        QualityPolicy.patchReqObject(req, targetQn = 80)
        assertEquals(4048, req.fnval)
        assertTrue(req.fourk)
        assertEquals(80L, req.qn)

        val reqHighest = DummyReq()
        QualityPolicy.patchReqObject(reqHighest, targetQn = -1)
        assertEquals(4048, reqHighest.fnval)
        assertTrue(reqHighest.fourk)
        assertEquals(127L, reqHighest.qn)
    }
}
