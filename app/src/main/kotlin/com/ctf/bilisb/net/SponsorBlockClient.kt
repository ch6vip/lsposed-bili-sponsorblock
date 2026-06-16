package com.ctf.bilisb.net

import com.ctf.bilisb.model.SponsorSegment
import com.ctf.bilisb.util.HashUtils
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SponsorBlockClient(
    private val baseUrl: String,
) {
    fun endpointForBvid(bvid: String): String {
        val prefix = HashUtils.hashPrefix(bvid)
        return "${baseUrl.trimEnd('/')}/api/skipSegments/$prefix"
    }

    fun fetchRawSkipSegments(bvid: String, cid: Long): String? {
        val url = URL(endpointForBvid(bvid) + buildQuery(bvid, cid))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Origin", "BiliRoamingX")
            setRequestProperty("X-EXT-VERSION", "1.27.3")
            setRequestProperty("cache-control", "no-cache")
            setRequestProperty("X-SKIP-CACHE", "1")
        }

        return runCatching {
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        }.getOrNull()
    }

    fun parseSegments(raw: String): List<SponsorSegment> {
        return emptyList()
    }

    private fun buildQuery(bvid: String, cid: Long): String {
        return "?videoID=${encode(bvid)}&cid=$cid&actionType=skip"
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}

