package com.ctf.bilisb.unlock

import com.ctf.bilisb.unlock.proto.PlayViewUniteReq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自备 protobuf schema（`bilisb_unlock.proto`）与**宿主实拍字节**的 round-trip 回归。
 *
 * 样本 `req_sample.bin` 是 U1 观测钩在国际版 6.6.0 宿主上实拍的
 * `PlayViewUniteReq.toByteArray()`（2018B，UGC 视频预取请求：bvid=BV14741127BN、extra_content 9 组，
 * vod 只带 qn/fnval/force_host/fourk/prefer_codec_type/qn_policy，无 cid）。
 *
 * 这张测试守的是解锁功能的**地基假设**：我们 schema 解析宿主实拍字节后，
 * 已知字段语义正确、未知字段（bvid/ad_extra/fragment 等 6.6.0 新字段）往返保真，
 * 重序列化与原始字节一致——U4 的「解析请求 → 重建响应」链路全部建立在这个假设上。
 */
class PlayViewUniteReqRoundTripTest {

    private val sample: ByteArray by lazy {
        javaClass.classLoader!!.getResourceAsStream("unlock/req_sample.bin")!!.readBytes()
    }

    @Test
    fun `实拍字节可被自备 schema 解析且语义正确`() {
        val req = PlayViewUniteReq.parseFrom(sample)

        assertEquals("BV14741127BN", req.bvid)
        assertEquals("united.player-video-detail.0.0", req.spmid)
        assertEquals("default-value", req.fromSpmid)
        // vod：cid 缺席（预取请求），画质参数齐全
        assertEquals(0L, req.vod.cid)
        assertEquals(32L, req.vod.qn)
        assertEquals(17364, req.vod.fnval)
        assertEquals(2, req.vod.forceHost)
        assertTrue(req.vod.fourk)
        assertEquals(2, req.vod.preferCodecType)
        // extra_content：9 组键值
        assertEquals(9, req.extraContentCount)
        assertEquals("LEVEL_L1", req.getExtraContentOrThrow("security_level"))
    }

    @Test
    fun `重序列化与宿主原始字节一致 未知字段往返保真`() {
        val reserialized = PlayViewUniteReq.parseFrom(sample).toByteArray()

        // 已知字段按号序 + 未知字段按原序补在后面，两者顺序与原始 wire 一致：
        // 字节级一致是本轮字段号实证的直接结果，破此假设即 schema 漂移
        assertTrue(
            "重序列化字节不一致：len ${reserialized.size} vs ${sample.size}",
            sample.contentEquals(reserialized),
        )
    }

    @Test
    fun `round-trip 二次解析语义不变`() {
        val again = PlayViewUniteReq.parseFrom(
            PlayViewUniteReq.parseFrom(sample).toByteArray(),
        )
        assertEquals("BV14741127BN", again.bvid)
        assertEquals(17364, again.vod.fnval)
        assertEquals(9, again.extraContentCount)
    }

    @Test
    fun `触碰字段可改写且不破坏未知字段`() {
        // U4 的 req 补参形态：改 fnval/download 后未知字段（bvid/ad_extra 等）仍在
        // 注意：javalite 的 Builder 没有 getVodBuilder()，用 setVod(重构后的 vod)
        val msg = PlayViewUniteReq.parseFrom(sample)
        val patched = msg.toBuilder()
            .setVod(msg.vod.toBuilder().setFnval(127).setDownload(0).build())
            .build()

        val patchedBytes = patched.toByteArray()
        val reparsed = PlayViewUniteReq.parseFrom(patchedBytes)

        assertEquals(127, reparsed.vod.fnval)
        assertEquals(0, reparsed.vod.download)
        assertEquals("BV14741127BN", reparsed.bvid)          // 未知字段保真
        assertEquals(9, reparsed.extraContentCount)          // 已知字段不丢
        assertTrue(patchedBytes.size > sample.size - 32)      // 改写不引发体积崩塌
    }
}
