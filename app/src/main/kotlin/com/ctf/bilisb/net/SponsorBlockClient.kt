package com.ctf.bilisb.net

import android.util.Log
import com.ctf.bilisb.model.SponsorBlockConfig
import com.ctf.bilisb.model.SponsorBlockQuery
import com.ctf.bilisb.model.SponsorBlockSubmission
import com.ctf.bilisb.model.SponsorSegment
import com.ctf.bilisb.util.HashUtils
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

    data class SubmitResult(
        val statusCode: Int,
        val body: String?,
    )

    // 简单的内存缓存
    private val segmentCache = ConcurrentHashMap<String, CacheEntry>()
    private data class CacheEntry(
        val segments: List<SponsorSegment>,
        val timestamp: Long,
        val ttlMs: Long = 5 * 60 * 1000, // 5分钟过期
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttlMs
    }

    fun endpointForBvid(bvid: String): String {
        val prefix = HashUtils.videoIdHashPrefix(bvid)
        return "${config.serverAddress.trimEnd('/')}/api/skipSegments/$prefix"
    }

    fun fetchSkipSegments(query: SponsorBlockQuery, ignoreCache: Boolean = false): FetchResult {
        val cacheKey = "${query.bvid}:${query.cid}"

        // 检查缓存
        if (!ignoreCache) {
            segmentCache[cacheKey]?.let { entry ->
                if (!entry.isExpired()) {
                    Log.d(TAG, "Using cached segments for $cacheKey")
                    return FetchResult(200, null, entry.segments)
                } else {
                    segmentCache.remove(cacheKey)
                }
            }
        }

        // 带重试的网络请求
        val result = retryRequest(maxRetries = 3) {
            fetchSegmentsInternal(query, ignoreCache)
        }

        // 缓存成功结果
        if (result.statusCode in 200..299 && result.segments.isNotEmpty()) {
            segmentCache[cacheKey] = CacheEntry(result.segments, System.currentTimeMillis())
            Log.d(TAG, "Cached ${result.segments.size} segments for $cacheKey")
        }

        return result
    }

    private fun fetchSegmentsInternal(query: SponsorBlockQuery, ignoreCache: Boolean): FetchResult {
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
        }.getOrElse { e ->
            Log.w(TAG, "Network request failed: ${e.message}")
            FetchResult(-1, null, emptyList())
        }.also {
            conn.disconnect()
        }
    }

    fun fetchRawSkipSegments(bvid: String, cid: Long): String? {
        return fetchSkipSegments(SponsorBlockQuery(bvid, cid)).body
    }

    fun submitSegment(submission: SponsorBlockSubmission, ignoreCache: Boolean = true): SubmitResult {
        // 提交也带重试
        return retryRequest(maxRetries = 2) {
            submitSegmentInternal(submission, ignoreCache)
        }
    }

    private fun submitSegmentInternal(submission: SponsorBlockSubmission, ignoreCache: Boolean): SubmitResult {
        val url = URL(buildSubmitUrl(submission))
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
            doInput = true
        }

        return runCatching {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            SubmitResult(status, body)
        }.getOrElse { e ->
            Log.w(TAG, "Submit request failed: ${e.message}")
            SubmitResult(-1, null)
        }.also {
            conn.disconnect()
        }
    }

    private fun <T> retryRequest(maxRetries: Int, block: () -> T): T {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val delayMs = (attempt + 1) * 500L
                    Log.d(TAG, "Retry ${attempt + 1}/$maxRetries after ${delayMs}ms")
                    Thread.sleep(delayMs)
                }
            }
        }
        throw lastException ?: Exception("Retry failed")
    }

    fun clearCache() {
        segmentCache.clear()
        Log.d(TAG, "Cache cleared")
    }

    companion object {
        private const val TAG = "SponsorBlockClient"
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

    fun buildSubmitUrl(submission: SponsorBlockSubmission): String {
        val base = "${config.serverAddress.trimEnd('/')}/api/skipSegments"
        val query = buildString {
            append("?userID=").append(encode(submission.userId))
            append("&videoID=").append(encode(submission.bvid))
            append("&cid=").append(submission.cid)
            append("&category=").append(encode(submission.category))
            append("&startTime=").append(formatSeconds(submission.startMs))
            append("&endTime=").append(formatSeconds(submission.endMs))
            append("&videoDuration=").append(formatSeconds(submission.videoDurationMs))
            if (submission.epId > 0) {
                append("&epId=").append(submission.epId)
            }
        }
        return base + query
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun formatSeconds(valueMs: Long): String {
        return String.format(Locale.US, "%.3f", valueMs / 1000.0)
    }
}
