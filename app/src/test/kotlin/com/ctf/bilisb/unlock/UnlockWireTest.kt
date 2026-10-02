package com.ctf.bilisb.unlock

import com.ctf.bilisb.unlock.proto.VodInfo
import com.google.protobuf.Any
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UnlockWire] 手工 wire bytes 的可解析性回归——重建响应走「wire bytes → 宿主类
 * parseFrom」路线，这里用**同字段号的自备 schema** 验证字节语义（宿主类不可离线实例化）。
 */
class UnlockWireTest {

    private val data = PlayurlData(
        quality = 80,
        format = "dash",
        timelength = 30000000L,
        videoCodecid = 12,
        videos = listOf(
            DashTrack(80, "http://mock/video-80.m4s", listOf("http://mock/bk-80"), 2000000, 12, "", 0),
        ),
        audios = listOf(
            DashTrack(30280, "http://mock/audio-30280.m4a", emptyList(), 320000, 0, "", 0),
        ),
    )

    @Test
    fun `vodInfo 字节可按同号 schema 解析且双轨语义正确`() {
        val bytes = UnlockWire.buildVodInfoBytes(data)
        val vodInfo = VodInfo.parseFrom(bytes)

        assertEquals(80, vodInfo.quality)
        assertEquals("dash", vodInfo.format)
        assertEquals(30000000L, vodInfo.timelength)
        assertEquals(12, vodInfo.videoCodecid)
        assertEquals(1, vodInfo.streamListCount)
        assertEquals("http://mock/video-80.m4s", vodInfo.getStreamList(0).dashVideo.baseUrl)
        assertEquals(listOf("http://mock/bk-80"), vodInfo.getStreamList(0).dashVideo.backupUrlList)
        assertEquals(1, vodInfo.dashAudioCount)
        assertEquals(30280, vodInfo.getDashAudio(0).id)
    }

    @Test
    fun `pgc 载荷携带 video_info 且 business_view_info 可清`() {
        val bytes = UnlockWire.buildPgcPayloadBytes(data)
        // 载荷与 PlayViewReply@v2 wire 同构（video_info=1, business=3, view_info=5）
        val pgc = com.ctf.bilisb.unlock.proto.PlayViewReply.parseFrom(bytes)
        assertEquals(80, pgc.videoInfo.quality)
        assertEquals(1, pgc.videoInfo.streamListCount)
        assertEquals("http://mock/video-80.m4s", pgc.videoInfo.getStreamList(0).dashVideo.baseUrl)
        assertFalse(pgc.viewInfo.hasDialog())
    }

    @Test
    fun `any 字节可被标准 Any schema 解析`() {
        val payload = byteArrayOf(1, 2, 3)
        val bytes = UnlockWire.buildAnyBytes(PlayViewDecision.PGC_ANY_MODEL_TYPE_URL, payload)
        val any = Any.parseFrom(bytes)
        assertEquals(PlayViewDecision.PGC_ANY_MODEL_TYPE_URL, any.typeUrl)
        assertTrue(payload.contentEquals(any.value.toByteArray()))
    }

    @Test
    fun `qn_panel 三层嵌套与宿主 StreamInfo 字段号一致`() {
        val withPanel = data.copy(
            formats = mapOf(
                80 to org.json.JSONObject(
                    """{"quality":80,"display_desc":"高清 1080P","new_description":"1080P 高清",
                        "superscript":"","need_vip":false,"need_login":false,"format":"HD"}""",
                ),
                112 to org.json.JSONObject(
                    """{"quality":112,"display_desc":"1080P 高码率","new_description":"高码率",
                        "superscript":"会员","need_vip":true,"need_login":false,"format":"HD"}""",
                ),
            ),
        )
        val vodInfo = VodInfo.parseFrom(UnlockWire.buildVodInfoBytes(withPanel))

        // qn_panel(12) → QnPanel{qn_items(1) → QnItem{stream_info(1)}}
        assertEquals(2, vodInfo.qnPanel.qnItemsCount)
        val streamInfo = vodInfo.qnPanel.getQnItems(0).streamInfo
        assertEquals(80, streamInfo.quality)
        assertEquals("高清 1080P", streamInfo.displayDesc)
        assertEquals("1080P 高清", streamInfo.newDescription)
        assertEquals("HD", streamInfo.format)
        assertFalse(streamInfo.needVip)
        // 会员画质条目保留元数据(角标/need_vip)——展示层语义与 BiliRoaming G0.q 一致
        val vip = vodInfo.qnPanel.getQnItems(1).streamInfo
        assertEquals(112, vip.quality)
        assertEquals("1080P 高码率", vip.displayDesc)
        assertEquals("会员", vip.superscript)
        assertTrue(vip.needVip)
    }

    @Test
    fun `无 support_formats 时不写 qn_panel 字段`() {
        val vodInfo = VodInfo.parseFrom(UnlockWire.buildVodInfoBytes(data))
        assertEquals(0, vodInfo.qnPanel.qnItemsCount)
        assertFalse(vodInfo.hasQnPanel())
    }
}

class RestrictedReplyPatchTest {

    /**
     * 真实 PGC 响应（6.6.0 宿主实拍 19625B 的 PlayViewUniteReply 完整序列化）。
     * U4 运行时的补丁路径与这里完全一致：unite reply → supplement.value →
     * PlayViewReply 清 view_info / is_preview=false → 重包 Any。
     */
    private val sample: ByteArray by lazy {
        javaClass.classLoader!!.getResourceAsStream("unlock/restricted_reply_sample.bin")!!.readBytes()
    }

    @Test
    fun `真实 unite reply 可被自备 schema 解析且 supplement 可提取`() {
        val reply = com.ctf.bilisb.unlock.proto.PlayViewUniteReply.parseFrom(sample)
        // supplement (Any) 存在且 type_url 为 PGC 模型
        assertEquals(PlayViewDecision.PGC_ANY_MODEL_TYPE_URL, reply.supplement.typeUrl)
        assertTrue(reply.supplement.value.size() > 0)
        // playArc.cid 为本集真实 cid
        assertEquals(40700545720L, reply.playArc.cid)
    }

    @Test
    fun `清弹窗补丁在真实 supplement 上保留未知字段 v2`() {
        val reply = com.ctf.bilisb.unlock.proto.PlayViewUniteReply.parseFrom(sample)
        val origPayload = reply.supplement.value.toByteArray()
        val origPgc = com.ctf.bilisb.unlock.proto.PlayViewReply.parseFrom(origPayload)

        // U4 运行时同款补丁：清 view_info + is_preview=false
        val patchedPayload = origPgc.toBuilder()
            .clearViewInfo()
            .setBusiness(origPgc.business.toBuilder().setIsPreview(false).build())
            .build()
            .toByteArray()

        val repatched = com.ctf.bilisb.unlock.proto.PlayViewReply.parseFrom(patchedPayload)
        // business 未知字段（episode_info 等 UI 数据）保留：体积接近原始
        val before = origPgc.business.toByteArray()
        val after = repatched.business.toByteArray()
        assertTrue("business 体积保留（未知字段不丢）", after.size >= before.size - 8)
        assertFalse("isPreview 应为 false", repatched.business.isPreview)
        // view_info 已清
        assertTrue("viewInfo 应已清空", !repatched.viewInfo.hasDialog() && repatched.viewInfo.dialog.type.isEmpty())
        // 载荷体积应缩小（清掉的 area_limit 弹窗内容）
        assertTrue("清弹窗后载荷应缩小", patchedPayload.size < origPayload.size)
    }
}

/**
 * TONIKAWA S2（僅限港澳台，真实受限内容）响应的字节级回归——2026-10-02 实拍。
 *
 * 关键事实：国际网关对受限内容返回的是「可用响应 + view_info.dialog(area_limit)」，
 * 不是错误形态。判定路径（PlayViewDecision 的 areaLimited 分支）以此样本钉死。
 */
class TonikawaRestrictedReplyTest {

    private val sample: ByteArray by lazy {
        javaClass.classLoader!!.getResourceAsStream("unlock/tonikawa_reply.bin")!!.readBytes()
    }

    @Test
    fun `真实受限响应的 dialog 判定路径验证`() {
        val reply = com.ctf.bilisb.unlock.proto.PlayViewUniteReply.parseFrom(sample)

        assertEquals(PlayViewDecision.PGC_ANY_MODEL_TYPE_URL, reply.supplement.typeUrl)
        val pgc = com.ctf.bilisb.unlock.proto.PlayViewReply.parseFrom(reply.supplement.value.toByteArray())

        assertEquals("area_limit", pgc.viewInfo.dialog.type)
        assertEquals("抱歉您所在地区不可观看！", pgc.viewInfo.dialog.msg)
        // 顶层 vodInfo 存在（国际网关受限形态：响应可用 + 弹窗，与国内 API 的错误形态不同）
        assertTrue(reply.hasVodInfo())
    }
}
