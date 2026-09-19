package com.ctf.bilisb.sponsor

import android.os.SystemClock
import com.ctf.bilisb.model.SponsorBlockQuery
import com.ctf.bilisb.model.SponsorBlockSubmission
import com.ctf.bilisb.model.SponsorSegment
import com.ctf.bilisb.net.SponsorBlockApi
import com.ctf.bilisb.net.SponsorBlockClient
import java.util.concurrent.ConcurrentHashMap

/**
 * 片段缓存 + 网络访问。
 *
 * TTL 判定使用**单调时钟** [SystemClock.elapsedRealtime](可由测试注入 [nowMs])。
 * 之前用墙钟 `System.currentTimeMillis()`:用户改系统时间或 NTP 回拨后,
 * `now - fetchedAt` 会变成负数,缓存就"永不过期"了。
 *
 * 缓存本身有容量上限 [MAX_CACHE_ENTRIES]:观看会话里每个视频都会写一条,
 * 不设上限会随观看量单调增长。写入时顺带清理过期条目,再按最旧淘汰。
 */
class SponsorBlockRepository(
    private val client: SponsorBlockApi = SponsorBlockClient(),
    private val cacheTtlMs: Long = 60L * 60_000L,
    private val nowMs: () -> Long = defaultClock(),
) {
    private val cache = ConcurrentHashMap<String, List<SponsorSegment>>()
    private val fetchedAt = ConcurrentHashMap<String, Long>()
    private data class Known(val segments: List<SponsorSegment>, val gen: Long, val at: Long)
    private val lastKnown = ConcurrentHashMap<String, Known>()
    /** 每次 [clear] 递增,用来丢掉「清缓存之后才返回」的在途写入。 */
    private val generation = ConcurrentHashMap<String, Long>()

    /** 仅供测试断言新鲜缓存条目数。 */
    internal val cacheSize: Int get() = cache.size

    fun currentGeneration(query: SponsorBlockQuery): Long = generation[cacheKey(query)] ?: 0L

    fun getCached(query: SponsorBlockQuery): List<SponsorSegment>? {
        if (cacheTtlMs <= 0) {
            val key = cacheKey(query)
            cache.remove(key)
            fetchedAt.remove(key)
            return null
        }
        val key = cacheKey(query)
        val cached = cache[key] ?: return null
        val ts = fetchedAt[key] ?: return null
        if (nowMs() - ts > cacheTtlMs) {
            // 过期:只清新鲜缓存,保留 lastKnown 给当前视频继续跳过。
            cache.remove(key)
            fetchedAt.remove(key)
            return null
        }
        return cached
    }

    fun getLastKnown(query: SponsorBlockQuery): List<SponsorSegment>? {
        val key = cacheKey(query)
        val known = lastKnown[key] ?: return null
        if (known.gen != (generation[key] ?: 0L)) {
            lastKnown.remove(key, known)
            return null
        }
        return known.segments
    }

    fun fetchAndCache(query: SponsorBlockQuery, ignoreCache: Boolean = false): SponsorBlockClient.FetchResult {
        val key = cacheKey(query)
        val gen = generation[key] ?: 0L
        val result = client.fetchSkipSegments(query, ignoreCache)
        if (result.parseFailed) {
            return result
        }
        if ((generation[key] ?: 0L) != gen) {
            return result
        }
        if (result.statusCode == 200 || result.statusCode == 404) {
            lastKnown[key] = Known(result.segments, gen, nowMs())
            if ((generation[key] ?: 0L) != gen) {
                lastKnown.remove(key)
                cache.remove(key)
                fetchedAt.remove(key)
                return result
            }
            evictLastKnownIfNeeded()
            if (cacheTtlMs > 0) {
                evictIfNeeded()
                cache[key] = result.segments
                fetchedAt[key] = nowMs()
            }
        }
        return result
    }

    fun clear(query: SponsorBlockQuery) {
        val key = cacheKey(query)
        generation.merge(key, 1L) { current, delta -> current + delta }
        cache.remove(key)
        fetchedAt.remove(key)
        lastKnown.remove(key)
    }

    fun submit(submission: SponsorBlockSubmission, ignoreCache: Boolean = true): SponsorBlockClient.SubmitResult {
        return client.submitSegment(submission, ignoreCache)
    }

    private fun removeEntry(key: String) {
        cache.remove(key)
        fetchedAt.remove(key)
    }

    /**
     * 写入前的淘汰:先清过期条目,仍然超过容量上限时按写入时刻淘汰最旧的几条。
     *
     * 淘汰到 [TARGET_CACHE_ENTRIES] 而不是刚好 [MAX_CACHE_ENTRIES]-1,避免之后每个
     * 新视频都触发一次全表扫描(每清一批可以顶很久)。
     */
    private fun evictIfNeeded() {
        val now = nowMs()
        for ((key, ts) in fetchedAt) {
            if (now - ts > cacheTtlMs) {
                removeEntry(key)
            }
        }
        if (cache.size < MAX_CACHE_ENTRIES) return

        fetchedAt.entries
            .sortedBy { it.value }
            .take(cache.size - TARGET_CACHE_ENTRIES)
            .forEach { removeEntry(it.key) }
    }

    private fun evictLastKnownIfNeeded() {
        if (lastKnown.size < MAX_CACHE_ENTRIES) return
        val extra = lastKnown.size - TARGET_CACHE_ENTRIES
        if (extra <= 0) return
        lastKnown.entries
            .sortedBy { it.value.at }
            .take(extra)
            .forEach { lastKnown.remove(it.key) }
    }

    private fun cacheKey(query: SponsorBlockQuery): String {
        // 只按 bvid 缓存:协议按 bvid hash 前缀拉取、客户端按 videoID 过滤,同一 bvid 的
        // 不同 cid(分P)拿到的是同一份数据 —— cid 进 key 会让切P白白重发网络请求。
        return query.bvid
    }

    /** 停掉底层 client 的网络线程池(controller 关闭时调用;测试用 Fake client 无需处理)。 */
    fun close() {
        (client as? SponsorBlockClient)?.close()
    }

    /** 继承上一个实例的缓存(controller 因设置变更重建时调用,避免当前视频片段短暂丢失)。 */
    internal fun transferCacheFrom(previous: SponsorBlockRepository) {
        cache.putAll(previous.cache)
        fetchedAt.putAll(previous.fetchedAt)
        lastKnown.putAll(previous.lastKnown)
        generation.putAll(previous.generation)
    }

    companion object {
        /** 缓存条目上限(bvid 维度)。 */
        private const val MAX_CACHE_ENTRIES = 32

        /** 触发淘汰后保留的条目数(给下一批写入留余量)。 */
        private const val TARGET_CACHE_ENTRIES = 24

        /**
         * 默认时钟：优先单调时钟 [SystemClock.elapsedRealtime]。
         *
         * 纯 JVM 单测里 `android.jar` 是 mockable stub（调用会抛 "not mocked"），
         * 这时退化为墙钟，这样不注入时钟也能直接构造仓库做单测；
         * 真机上依然是单调时钟（改系统时间/NTP 回拨不会让缓存永不过期）。
         */
        private fun defaultClock(): () -> Long {
            val monotonicAvailable = runCatching { SystemClock.elapsedRealtime() }.isSuccess
            return if (monotonicAvailable) {
                { SystemClock.elapsedRealtime() }
            } else {
                { System.currentTimeMillis() }
            }
        }
    }
}
