package com.ctf.bilisb.unlock

import com.ctf.bilisb.unlock.PlayViewDecision.Facts
import com.ctf.bilisb.unlock.PlayViewDecision.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PlayViewDecision] 受限判定的回归。
 *
 * 判定顺序即行为（与 BiliRoaming needProxyUnite/isThai 的分层同口径）：
 * THAI_REDIRECT 优先于一切；UGC 短路与「非番剧请求」放行在其后；
 * 剩下的按「响应是否带 PGC supplement」二分。isDownload 绕过两道放行门。
 */
class PlayViewDecisionTest {

    private fun facts(
        reqVodCid: Long = 0,
        seasonId: String = "0",
        epId: String = "0",
        isDownload: Boolean = false,
        respUsable: Boolean = true,
        respPlayArcCid: Long = 0,
        supplementTypeUrl: String? = null,
    ) = Facts(
        reqVodCid = reqVodCid,
        seasonId = seasonId,
        epId = epId,
        isDownload = isDownload,
        respUsable = respUsable,
        respPlayArcCid = respPlayArcCid,
        supplementTypeUrl = supplementTypeUrl,
    )

    @Test
    fun `普通视频 UGC supplement 直接放行`() {
        val v = PlayViewDecision.classify(
            facts(supplementTypeUrl = PlayViewDecision.UGC_ANY_MODEL_TYPE_URL),
        )
        assertEquals(Verdict.NORMAL_UGC, v)
    }

    @Test
    fun `番剧请求且响应带 PGC supplement 判为正常`() {
        val v = PlayViewDecision.classify(
            facts(
                reqVodCid = 168885122,
                epId = "285145",
                seasonId = "12345",
                respPlayArcCid = 168885122,
                supplementTypeUrl = PlayViewDecision.PGC_ANY_MODEL_TYPE_URL,
            ),
        )
        assertEquals(Verdict.NORMAL_PGC, v)
    }

    @Test
    fun `番剧请求但 supplement 非 PGC 按 UGC 短路放行`() {
        // 参考实现的门槛顺序：UGC 短路（typeUrl != PGC）发生在番剧请求检查之前——
        // 所以这一形态按 NORMAL_UGC 放行；受限的主信号（area_limit 弹窗）在
        // supplement 字节内部，U2 proto 管线后并入判定。
        val v = PlayViewDecision.classify(
            facts(
                reqVodCid = 168885122,
                epId = "285145",
                seasonId = "12345",
                respPlayArcCid = 168885122,
                supplementTypeUrl = PlayViewDecision.UGC_ANY_MODEL_TYPE_URL,
            ),
        )
        assertEquals(Verdict.NORMAL_UGC, v)
    }

    @Test
    fun `响应不可用（缺 vodInfo）时番剧请求判为受限`() {
        val v = PlayViewDecision.classify(
            facts(
                reqVodCid = 168885122,
                epId = "285145",
                seasonId = "12345",
                respUsable = false,
                respPlayArcCid = 168885122,
            ),
        )
        assertEquals(Verdict.RESTRICTED, v)
    }

    @Test
    fun `请求 cid 与响应 cid 不一致判为重定向 且优先于其他判定`() {
        // 重定向优先于 NORMAL_PGC
        val thai = PlayViewDecision.classify(
            facts(
                reqVodCid = 111,
                epId = "285145",
                seasonId = "1",
                respPlayArcCid = 222,
                supplementTypeUrl = PlayViewDecision.PGC_ANY_MODEL_TYPE_URL,
            ),
        )
        assertEquals(Verdict.THAI_REDIRECT, thai)

        // 重定向优先于 UGC 短路：season/ep 全零也不能放行
        val thaiNoSeason = PlayViewDecision.classify(
            facts(
                reqVodCid = 111,
                respPlayArcCid = 222,
                supplementTypeUrl = PlayViewDecision.UGC_ANY_MODEL_TYPE_URL,
            ),
        )
        assertEquals(Verdict.THAI_REDIRECT, thaiNoSeason)
    }

    @Test
    fun `非番剧请求放行 不进入受限判定`() {
        val v = PlayViewDecision.classify(
            facts(respUsable = true, supplementTypeUrl = null),
        )
        assertEquals(Verdict.NORMAL_UGC, v)
    }

    @Test
    fun `下载请求绕过 UGC 短路与非番剧放行`() {
        // 同样的"非番剧请求"事实，isDownload=true 时不再放行
        val v = PlayViewDecision.classify(
            facts(isDownload = true, respUsable = true, supplementTypeUrl = null),
        )
        assertEquals(Verdict.RESTRICTED, v)

        // UGC supplement 下载请求同样进入受限判定（缓存解锁要改写它的 download 字段）
        val v2 = PlayViewDecision.classify(
            facts(isDownload = true, respUsable = true, supplementTypeUrl = PlayViewDecision.UGC_ANY_MODEL_TYPE_URL),
        )
        assertEquals(Verdict.RESTRICTED, v2)
    }

    @Test
    fun `typeUrl 常量与宿主 dex 实证值一致`() {
        assertEquals(
            "type.googleapis.com/bilibili.app.playerunite.pgcanymodel.PGCAnyModel",
            PlayViewDecision.PGC_ANY_MODEL_TYPE_URL,
        )
        assertEquals(
            "type.googleapis.com/bilibili.app.playerunite.ugcanymodel.UGCAnyModel",
            PlayViewDecision.UGC_ANY_MODEL_TYPE_URL,
        )
    }
}
