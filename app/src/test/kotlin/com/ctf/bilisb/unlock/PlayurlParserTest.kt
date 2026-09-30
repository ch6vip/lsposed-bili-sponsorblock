package com.ctf.bilisb.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PlayurlParser] 的回归：漫游服务器返回的经典 playurl JSON → 中间模型。
 * 样本形状与 `tools/mock-roamer/mock_roamer.py` 的 canned 响应一致（U4 闭环的输入）。
 */
class PlayurlParserTest {

    private val dashJson = """
    {
      "code": 0,
      "quality": 80,
      "format": "dash",
      "type": "DASH",
      "timelength": 30000000,
      "video_codecid": 12,
      "accept_quality": [80, 64],
      "support_formats": [{"quality": 80, "new_description": "高清 1080P"}],
      "dash": {
        "video": [
          {"id": 80, "base_url": "http://mock/video-80.m4s", "backup_url": ["http://mock/bk-80"],
           "bandwidth": 2000000, "codecid": 12, "md5": "vmd5", "size": 100000},
          {"id": 64, "base_url": "http://mock/video-64.m4s", "backup_url": [],
           "bandwidth": 1200000, "codecid": 12, "md5": "", "size": 0},
          {"id": 80, "base_url": "http://mock/video-80-av1.m4s", "backup_url": [],
           "bandwidth": 1800000, "codecid": 13, "md5": "", "size": 0}
        ],
        "audio": [
          {"id": 30280, "base_url": "http://mock/audio-30280.m4s", "backup_url": [],
           "bandwidth": 320000, "codecid": 0, "md5": "", "size": 0}
        ]
      }
    }
    """.trimIndent()

    @Test
    fun `解析 DASH 双轨与画质字段`() {
        val data = PlayurlParser.parse(dashJson)!!

        assertEquals(80, data.quality)
        assertEquals("dash", data.format)
        assertEquals(30000000L, data.timelength)
        assertEquals(12, data.videoCodecid)
        // 三条视频轨全部保留（无 prefer 过滤时）
        assertEquals(3, data.videos.size)
        assertEquals(1, data.audios.size)
        assertEquals("http://mock/video-80.m4s", data.videos[0].baseUrl)
        assertEquals(listOf("http://mock/bk-80"), data.videos[0].backupUrls)
        assertEquals(30280, data.audios[0].id)
    }

    @Test
    fun `preferCodec 过滤保留全部清晰度`() {
        // prefer=12 时只留 codecid=12 的两条，但 id 集合必须覆盖原全集（否则回退全量）
        val data = PlayurlParser.parse(dashJson, preferCodecId = 12)!!
        assertEquals(2, data.videos.size)
        assertEquals(setOf(80, 64), data.videos.map { it.id }.toSet())
    }

    @Test
    fun `code 非 0 返回 null`() {
        assertNull(PlayurlParser.parse("""{"code":-404,"message":"no"}"""))
    }

    @Test
    fun `缺 dash 返回 null`() {
        assertNull(PlayurlParser.parse("""{"code":0,"quality":80}"""))
    }

    @Test
    fun `kghost 形态外层 result 包装可解`() {
        val wrapped = """{"code":0,"result":$dashJson}"""
        val data = PlayurlParser.parse(wrapped)!!
        assertEquals(80, data.quality)
        assertEquals(2, data.videos.count { it.codecid == 12 })
    }
}
