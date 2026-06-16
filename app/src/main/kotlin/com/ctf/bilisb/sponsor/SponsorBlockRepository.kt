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

    fun getCached(query: SponsorBlockQuery): List<SponsorSegment>? {
        return cache[cacheKey(query)]
    }

    fun fetchAndCache(query: SponsorBlockQuery, ignoreCache: Boolean = false): SponsorBlockClient.FetchResult {
        val result = client.fetchSkipSegments(query, ignoreCache)
        if (result.statusCode == 200 || result.statusCode == 404) {
            cache[cacheKey(query)] = result.segments
        }
        return result
    }

    fun clear(query: SponsorBlockQuery) {
        cache.remove(cacheKey(query))
    }

    fun submit(submission: SponsorBlockSubmission, ignoreCache: Boolean = true): SponsorBlockClient.SubmitResult {
        return client.submitSegment(submission, ignoreCache)
    }

    private fun cacheKey(query: SponsorBlockQuery): String {
        return "${query.bvid}:${query.cid}:${query.actionType}"
    }
}
