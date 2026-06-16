package com.ctf.bilisb.net

import com.ctf.bilisb.model.SponsorBlockConfig
import com.ctf.bilisb.model.SponsorBlockQuery
import com.ctf.bilisb.model.SponsorSegment
import com.ctf.bilisb.util.HashUtils
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

class SponsorBlockClient(
    private val config: SponsorBlockConfig = SponsorBlockConfig(),
) {
    data class FetchResult(
        val statusCode: Int,
        val body: String?,
        val segments: List<SponsorSegment>,
    )

    fun endpointForBvid(bvid: String): String {
        val prefix = HashUtils.videoIdHashPrefix(bvid)
        return "${config.serverAddress.trimEnd('/')}/api/skipSegments/$prefix"
    }

    fun fetchSkipSegments(query: SponsorBlockQuery, ignoreCache: Boolean = false): FetchResult {
        val url = URL(endpointForBvid(query.bvid) + buildQuery(query))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Origin", "BiliRoamingX")
            setRequestProperty("X-EXT-VERSION", "1.27.3")
            if (ignoreCache) {
                setRequestProperty("cache-control", "no-cache")
                setRequestProperty("X-SKIP-CACHE", "1")
            }
        }

        return runCatching {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            FetchResult(status, body, parseSegmentsForVideo(query.bvid, body))
        }.getOrElse {
            FetchResult(-1, null, emptyList())
        }.also {
            conn.disconnect()
        }
    }

    fun fetchRawSkipSegments(bvid: String, cid: Long): String? {
        return fetchSkipSegments(SponsorBlockQuery(bvid, cid)).body
    }

    fun parseSegments(raw: String): List<SponsorSegment> {
        return parseSegmentsForVideo(null, raw)
    }

    fun parseSegmentsForVideo(bvid: String?, raw: String?): List<SponsorSegment> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        val buckets = JSONArray(raw)
        val result = mutableListOf<SponsorSegment>()
        for (i in 0 until buckets.length()) {
            val bucket = buckets.optJSONObject(i) ?: continue
            val videoId = bucket.optString("videoID", "")
            if (bvid != null && videoId != bvid) {
                continue
            }
            val segments = bucket.optJSONArray("segments") ?: continue
            for (j in 0 until segments.length()) {
                val segment = parseSegment(segments.optJSONObject(j) ?: continue) ?: continue
                if (segment.category !in config.enabledCategories) {
                    continue
                }
                if (segment.actionType !in config.enabledActionTypes) {
                    continue
                }
                result += segment
            }
        }
        return result.sortedBy { it.startMs }
    }

    private fun parseSegment(json: JSONObject): SponsorSegment? {
        val segmentArray = json.optJSONArray("segment") ?: return null
        if (segmentArray.length() < 2) {
            return null
        }

        // SponsorBlock API returns seconds. The Android player APIs use ms, so
        // normalize here and keep the rest of the module in player-native units.
        val startMs = (segmentArray.optDouble(0, 0.0) * 1000.0).toLong()
        val endMs = (segmentArray.optDouble(1, 0.0) * 1000.0).toLong()
        if (endMs < startMs) {
            return null
        }

        return SponsorSegment(
            category = json.optString("category", "sponsor"),
            actionType = json.optString("actionType", "skip"),
            segment = longArrayOf(startMs, endMs),
            uuid = json.optString("UUID", json.optString("uuid", "")),
            videoDuration = json.optDouble("videoDuration", 0.0),
            locked = json.optBoolean("locked", false),
            votes = json.optLong("votes", 0L),
            description = json.optString("description", ""),
        )
    }

    private fun buildQuery(query: SponsorBlockQuery): String {
        return "?videoID=${encode(query.bvid)}&cid=${query.cid}&actionType=${encode(query.actionType)}"
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
