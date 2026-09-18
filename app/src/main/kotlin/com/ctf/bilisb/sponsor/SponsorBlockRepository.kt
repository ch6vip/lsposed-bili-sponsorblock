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

    /** 仅供测试断言缓存条目数。 */
    internal val cacheSize: Int get() = cache.size

    fun getCached(query: SponsorBlockQuery): List<SponsorSegment>? {
        if (cacheTtlMs <= 0) {
            clear(query)
            return null
        }
        val key = cacheKey(query)
        val cached = cache[key] ?: return null
        val ts = fetchedAt[key] ?: return null
        if (nowMs() - ts > cacheTtlMs) {
            // 过期:清掉并返回 null,触发调用方重拉。
            removeEntry(key)
            return null
        }
        return cached
    }

    fun fetchAndCache(query: SponsorBlockQuery, ignoreCache: Boolean = false): SponsorBlockClient.FetchResult {
        val result = client.fetchSkipSegments(query, ignoreCache)
        // 200 但解析失败(截断/CDN 错误页)的响应不可信:不能当「该视频无片段」缓存满一个 TTL,
        // 否则期间所有跳过/静音整体失效且命中缓存后连重拉都不发生。只返回不缓存。
        if (result.parseFailed) {
            return result
        }
        if (cacheTtlMs > 0 && (result.statusCode == 200 || result.statusCode == 404)) {
            val key = cacheKey(query)
            evictIfNeeded()
            cache[key] = result.segments
            fetchedAt[key] = nowMs()
        }
        return result
    }

    fun clear(query: SponsorBlockQuery) {
        removeEntry(cacheKey(query))
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
