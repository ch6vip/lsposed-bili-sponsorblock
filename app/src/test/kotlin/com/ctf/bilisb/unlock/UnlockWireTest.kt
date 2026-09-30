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
    fun `pgc 载荷含空的 business 与 view_info 清弹窗`() {
        val bytes = UnlockWire.buildPgcPayloadBytes()
        // 载荷与 PlayViewReply@v2 wire 同构（business=3, view_info=5）；
        // 这里用宿主同族的 v2 形状断言存在性：直接按 Any 载荷字节断言长度与标签
        assertEquals(4, bytes.size)  // 2 个空消息字段：tag(1B)+len(1B) ×2
        assertEquals(0x1a, bytes[0].toInt() and 0xff)  // field 3, wt 2 → (3<<3)|2 = 26 = 0x1a
    }

    @Test
    fun `any 字节可被标准 Any schema 解析`() {
        val payload = byteArrayOf(1, 2, 3)
        val bytes = UnlockWire.buildAnyBytes(PlayViewDecision.PGC_ANY_MODEL_TYPE_URL, payload)
        val any = Any.parseFrom(bytes)
        assertEquals(PlayViewDecision.PGC_ANY_MODEL_TYPE_URL, any.typeUrl)
        assertTrue(payload.contentEquals(any.value.toByteArray()))
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