package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorBlockQuery
import com.ctf.bilisb.model.SponsorBlockSubmission
import com.ctf.bilisb.model.SponsorSegment
import com.ctf.bilisb.net.SponsorBlockClient
import java.util.concurrent.ConcurrentHashMap

class SponsorBlockRepository(
    private val client: SponsorBlockClient = SponsorBlockClient(),
) {
    private val cache = ConcurrentHashMap<String, List<SponsorSegment>>()
    private val fetchedAt = ConcurrentHashMap<String, Long>()

    /**
     * 片段缓存有效期。APK 不做 TTL(靠切集重拉 + X-SKIP-CACHE 头),
     * 这里加一层 TTL 是为了切回同一视频时能周期性刷新,默认 1 小时。
     */
    private val cacheTtlMs = 60L * 60 * 1000

    fun getCached(query: SponsorBlockQuery): List<SponsorSegment>? {
        val key = cacheKey(query)
        val cached = cache[key] ?: return null
        val ts = fetchedAt[key] ?: 0L
        if (ts != 0L && System.currentTimeMillis() - ts > cacheTtlMs) {
            // 过期:清掉并返回 null,触发调用方重拉。
            cache.remove(key)
            fetchedAt.remove(key)
            return null
        }
        return cached
    }

    fun fetchAndCache(query: SponsorBlockQuery, ignoreCache: Boolean = false): SponsorBlockClient.FetchResult {
        val result = client.fetchSkipSegments(query, ignoreCache)
        if (result.statusCode == 200 || result.statusCode == 404) {
            val key = cacheKey(query)
            cache[key] = result.segments
            fetchedAt[key] = System.currentTimeMillis()
        }
        return result
    }

    fun clear(query: SponsorBlockQuery) {
        val key = cacheKey(query)
        cache.remove(key)
        fetchedAt.remove(key)
    }

    fun submit(submission: SponsorBlockSubmission, ignoreCache: Boolean = true): SponsorBlockClient.SubmitResult {
        return client.submitSegment(submission, ignoreCache)
    }

    private fun cacheKey(query: SponsorBlockQuery): String {
        return "${query.bvid}:${query.cid}:${query.actionType}"
    }
}

