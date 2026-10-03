package com.ctf.bilisb.unlock

import org.json.JSONObject

/**
 * 漫游服务器返回的经典 playurl JSON → 中间模型。纯 JVM，可单测。
 *
 * 映射口径与参考实现的 toVideoInfo 一致（协议事实）：DASH 形态取
 * `dash.video[]`（按 preferCodec 过滤出可用的全集）与 `dash.audio[]`；
 * 字段名是宿主 VodInfo/Stream/DashVideo 的 wire 语义。
 */
data class DashTrack(
    val id: Int,
    val baseUrl: String,
    val backupUrls: List<String>,
    val bandwidth: Int,
    val codecid: Int,
    val md5: String,
    val size: Long,
    /** 该流的 stream_info 元数据（真实响应每条 Stream 都带；缺失会被播放器选流逻辑忽略）。 */
    val meta: StreamMeta? = null,
)

/** Stream 子消息 stream_info 的元数据（字段号实测，见 bilisb_unlock.proto）。 */
data class StreamMeta(
    val quality: Int,
    val format: String,
    val description: String,
    val newDescription: String,
)

data class PlayurlData(
    val quality: Int,
    val format: String,
    val timelength: Long,
    val videoCodecid: Int,
    /** dash.video 轨（按 codecid 过滤后的优选集，含全部清晰度）。 */
    val videos: List<DashTrack>,
    /** dash.audio 轨。 */
    val audios: List<DashTrack>,
    /** support_formats: quality → 描述元数据（stream_info 构建用）。 */
    val formats: Map<Int, JSONObject> = emptyMap(),
)

object PlayurlParser {

    /**
     * 解析经典 playurl JSON。`code != 0` 或缺 dash 时返回 null（调用方降级放行）。
     * 兼容 kghost 形态：外层可能包一层 `result`。
     */
    fun parse(content: String, preferCodecId: Int? = null): PlayurlData? = runCatching {
        var json = JSONObject(content)
        json.opt("result")?.let { result ->
            if (result !is String) json = json.getJSONObject("result")
        }
        if (json.optInt("code", 0) != 0) return null
        val dash = json.optJSONObject("dash") ?: return null

        fun tracks(key: String): List<DashTrack> {
            val arr = dash.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                DashTrack(
                    id = t.optInt("id"),
                    baseUrl = t.optString("base_url"),
                    backupUrls = buildList {
                        val bk = t.optJSONArray("backup_url")
                        for (j in 0 until (bk?.length() ?: 0)) bk?.optString(j)?.let { add(it) }
                    },
                    bandwidth = t.optInt("bandwidth"),
                    codecid = t.optInt("codecid"),
                    md5 = t.optString("md5"),
                    size = t.optLong("size"),
                )
            }
        }

        // support_formats: quality → 描述元数据（stream_info / qn_panel 用）。
        // LinkedHashMap 保序:面板条目按服务器下发的顺序展示（通常高→低）。
        val formatMap = LinkedHashMap<Int, JSONObject>()
        val fmtArr = json.optJSONArray("support_formats")
        if (fmtArr != null) {
            for (i in 0 until fmtArr.length()) {
                val f = fmtArr.optJSONObject(i) ?: continue
                formatMap[f.optInt("quality")] = f
            }
        }
        val videos = tracks("video").map { t ->
            val f = formatMap[t.id]
            if (f == null) t else t.copy(
                meta = StreamMeta(
                    quality = f.optInt("quality", t.id),
                    format = f.optString("codecs", json.optString("format")),
                    description = f.optString("new_description"),
                    newDescription = f.optString("display_desc", f.optString("new_description")),
                ),
            )
        }
        val filtered = preferCodecId?.let { prefer ->
            videos.filter { it.codecid == prefer }
                .takeIf { picked -> picked.map { it.id }.containsAll(videos.map { it.id }.toSet()) }
        } ?: videos

        PlayurlData(
            quality = json.optInt("quality"),
            format = json.optString("format"),
            timelength = json.optLong("timelength"),
            videoCodecid = json.optInt("video_codecid"),
            videos = filtered.ifEmpty { videos },
            audios = tracks("audio"),
            formats = formatMap,
        )
    }.getOrNull()
}

/** 选集面板的剧集条目（view.v1.ViewEpisode / viewunite.common.ViewEpisode 的字段语义）。 */
data class SeasonEpisode(
    val epId: Long,
    val badge: String,
    val badgeType: Int,
    val duration: Long,
    val status: Int,
    val cover: String,
    val aid: Long,
    val title: String,
    val longTitle: String,
    val cid: Long,
    val epIndex: Int,
)

/** 选集分区（viewunite.common.SectionData 的字段语义）。 */
data class SeasonSection(
    val id: Int,
    val sectionId: Int,
    val title: String,
    val type: Int,
    val episodes: List<SeasonEpisode>,
)

/**
 * CN season JSON（api.bilibili.com/pgc/view/web/season 匿名可取，CN 直连）
 * → 选集面板中间模型。字段名与 CN 接口对齐：result.seasons[] 每季含 episodes[]。
 */
object SeasonParser {

    fun parseEpisodes(arr: org.json.JSONArray?): List<SeasonEpisode> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            SeasonEpisode(
                epId = e.optLong("id"),
                badge = e.optString("badge"),
                badgeType = e.optInt("badge_type"),
                duration = e.optLong("duration"),
                status = e.optInt("status"),
                cover = e.optString("cover"),
                aid = e.optLong("aid"),
                title = e.optString("title"),
                longTitle = e.optString("long_title"),
                cid = e.optLong("cid"),
                epIndex = e.optInt("index", i + 1),
            )
        }
    }

    /** 解析 seasons[] → 分区列表（每季一个分区，episodes 内联）。 */
    fun parseSections(content: String): List<SeasonSection> = runCatching {
        var json = org.json.JSONObject(content)
        json.opt("result")?.let { result ->
            if (result !is String) json = json.getJSONObject("result")
        }
        val seasons = json.optJSONArray("seasons") ?: return emptyList()
        (0 until seasons.length()).mapNotNull { i ->
            val s = seasons.optJSONObject(i) ?: return@mapNotNull null
            val sid = s.optInt("season_id")
            SeasonSection(
                id = sid,
                sectionId = sid,
                title = s.optString("title"),
                type = i, // 首个分区（当前季）由调用方排序保证在前
                episodes = parseEpisodes(s.optJSONArray("episodes")),
            )
        }
    }.getOrDefault(emptyList())
}
