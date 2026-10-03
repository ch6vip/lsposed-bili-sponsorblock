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

/**
 * 选集面板两个 reply 构建器的 wire 回归。宿主侧曾在这里翻过车：字段层级拍平
 * （把 StreamInfo 字段号写进 QnItem 层）会让宿主 parseFrom 抛 "invalid tag (zero)"。
 * 剧集条目（episodes=7）与 episode_ids（=6）同理是消息/标量分立的两条 repeated——
 * 这里用同字段号自备 schema 钉死层级（宿主类不可离线实例化）。
 */
class SeasonWireBuildersTest {

    private val episodes = listOf(
        SeasonEpisode(
            epId = 744345L, badge = "会员", badgeType = 1, duration = 1420000L, status = 13,
            cover = "http://mock/cover.jpg", aid = 90000001L, title = "1",
            longTitle = "第一话", cid = 40700545720L, epIndex = 1,
        ),
        SeasonEpisode(
            epId = 744346L, badge = "", badgeType = 0, duration = 1400000L, status = 2,
            cover = "http://mock/cover2.jpg", aid = 90000002L, title = "2",
            longTitle = "第二话", cid = 40700545721L, epIndex = 2,
        ),
    )

    private val sections = listOf(
        SeasonSection(id = 1, sectionId = 328806, title = "第二季", type = 1, episodes = episodes),
    )

    @Test
    fun `seasonSections 字节按同号 schema 解析且层级正确`() {
        val reply = com.ctf.bilisb.unlock.proto.SeasonSectionsReply.parseFrom(
            UnlockWire.buildSeasonSectionsReplyBytes(sections),
        )

        assertEquals(1, reply.sectionsCount)
        val section = reply.getSections(0)
        assertEquals(328806, section.sectionId)
        assertEquals("第二季", section.title)
        assertEquals(listOf(744345L, 744346L), section.episodeIdsList)
        // 剧集条目是消息类型（wire type 2）——拍平写会在此解析失败
        assertEquals(2, section.episodesCount)
        val ep = section.getEpisodes(0)
        assertEquals(744345L, ep.epId)
        assertEquals("会员", ep.badge)
        assertEquals(40700545720L, ep.cid)
        assertEquals(90000001L, ep.aid)
        assertEquals("第一话", ep.longTitle)
        assertEquals(1, ep.epIndex)
    }

    @Test
    fun `pageSectionEpisodes 字节携带剧集与 section_id`() {
        val reply = com.ctf.bilisb.unlock.proto.PageSectionEpisodesReply.parseFrom(
            UnlockWire.buildPageSectionEpisodesReplyBytes(328806, episodes),
        )

        assertEquals(328806, reply.sectionId)
        assertEquals(2, reply.episodesCount)
        assertEquals(744346L, reply.getEpisodes(1).epId)
        assertEquals(40700545721L, reply.getEpisodes(1).cid)
    }

    @Test
    fun `空 episodes 的 section 仍写分区骨架`() {
        val reply = com.ctf.bilisb.unlock.proto.SeasonSectionsReply.parseFrom(
            UnlockWire.buildSeasonSectionsReplyBytes(
                listOf(SeasonSection(id = 2, sectionId = 9, title = "OVA", type = 3, episodes = emptyList())),
            ),
        )

        assertEquals(1, reply.sectionsCount)
        assertEquals("OVA", reply.getSections(0).title)
        assertEquals(0, reply.getSections(0).episodesCount)
    }
}

/** SeasonParser：CN season JSON（pgc/view/web/season）→ 选集中间模型的映射回归。 */
class SeasonParserTest {

    @Test
    fun `CN season JSON 解析为分区与剧集`() {
        val json = """
            {"code":0,"result":{"seasons":[
                {"season_id":328806,"title":"第二季","episodes":[
                    {"id":744345,"badge":"会员","badge_type":1,"duration":1420000,"status":13,
                     "cover":"http://mock/c.jpg","aid":90000001,"title":"1","long_title":"第一话",
                     "cid":40700545720,"index":1}]},
                {"season_id":328805,"title":"第一季","episodes":[]}
            ]}}
        """.trimIndent()

        val sections = SeasonParser.parseSections(json)

        assertEquals(2, sections.size)
        assertEquals(328806, sections[0].sectionId)
        assertEquals("第二季", sections[0].title)
        assertEquals(1, sections[0].episodes.size)
        assertEquals(744345L, sections[0].episodes[0].epId)
        assertEquals(40700545720L, sections[0].episodes[0].cid)
        assertEquals(1, sections[0].episodes[0].epIndex)
        assertEquals(0, sections[1].episodes.size)
    }

    @Test
    fun `缺 seasons 字段返回空列表不抛`() {
        assertTrue(SeasonParser.parseSections("""{"code":-404,"message":"啥都木有"}""").isEmpty())
    }
}
