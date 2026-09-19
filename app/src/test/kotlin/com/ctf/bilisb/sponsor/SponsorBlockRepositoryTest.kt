package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorBlockQuery
import com.ctf.bilisb.model.SponsorBlockSubmission
import com.ctf.bilisb.model.SponsorSegment
import com.ctf.bilisb.net.SponsorBlockApi
import com.ctf.bilisb.net.SponsorBlockClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockRepositoryTest {
    @Test
    fun caches200And404ResponsesUntilTtlExpires() {
        val clock = FakeClock()
        val api = FakeApi(
            fetchResults = ArrayDeque(
                listOf(
                    fetchResult(200, listOf(segment("u1"))),
                    fetchResult(404, emptyList()),
                )
            )
        )
        val repository = SponsorBlockRepository(api, cacheTtlMs = 1_000L, nowMs = clock::now)
        val query = SponsorBlockQuery("BV17x411w7KC", 123L)

        clock.advanceBy(1L)
        repository.fetchAndCache(query)
        assertEquals(listOf("u1"), repository.getCached(query)?.map { it.uuid })

        clock.advanceBy(1_001L)
        assertNull(repository.getCached(query))

        repository.fetchAndCache(query)
        assertEquals(emptyList<SponsorSegment>(), repository.getCached(query))
        assertEquals(2, api.fetchCalls.size)
    }

    @Test
    fun clearDropsOnlyRepositoryCacheSoNextFetchUsesApiAgain() {
        val api = FakeApi(
            fetchResults = ArrayDeque(
                listOf(
                    fetchResult(200, listOf(segment("old"))),
                    fetchResult(200, listOf(segment("fresh"))),
                )
            )
        )
        val repository = SponsorBlockRepository(api, cacheTtlMs = 60_000L)
        val query = SponsorBlockQuery("BV17x411w7KC", 123L)

        repository.fetchAndCache(query)
        repository.clear(query)
        repository.fetchAndCache(query)

        assertEquals(listOf("fresh"), repository.getCached(query)?.map { it.uuid })
        assertEquals(2, api.fetchCalls.size)
    }

    @Test
    fun ttlZeroDisablesCaching() {
        val api = FakeApi(fetchResults = ArrayDeque(listOf(fetchResult(200, listOf(segment("u1"))))))
        val repository = SponsorBlockRepository(api, cacheTtlMs = 0L)
        val query = SponsorBlockQuery("BV17x411w7KC", 123L)

        repository.fetchAndCache(query)

        assertNull(repository.getCached(query))
        assertEquals(listOf("u1"), repository.getLastKnown(query)?.map { it.uuid })
    }

    /**
     * TTL 走单调时钟:注入的钟不前进时,即使真实时间流逝缓存也不过期;
     * 注入的钟一前进超过 TTL 就立刻过期。
     *
     * (生产实现用 [android.os.SystemClock.elapsedRealtime],改系统时间/NTP 回拨不会影响缓存。)
     */
    @Test
    fun ttlUsesInjectedMonotonicClockOnly() {
        val clock = FakeClock()
        val api = FakeApi(fetchResults = ArrayDeque(listOf(fetchResult(200, listOf(segment("u1"))))))
        val repository = SponsorBlockRepository(api, cacheTtlMs = 5_000L, nowMs = clock::now)
        val query = SponsorBlockQuery("BV17x411w7KC", 123L)

        repository.fetchAndCache(query)

        clock.advanceBy(4_999L)
        assertNotNull(repository.getCached(query))

        clock.advanceBy(2L)
        assertNull(repository.getCached(query))
        // 过期条目在 getCached 里就被清掉了（不是留到下次写入才清）
        assertEquals(0, repository.cacheSize)
    }

    /**
     * 缓存 key 只按 bvid:协议按 bvid hash 前缀拉取、客户端按 videoID 过滤,
     * 同一 bvid 不同 cid(分P)是同一份数据,切 P 不应重发网络请求。
     */
    @Test
    fun sameBvidSharesSingleCacheEntryAcrossCids() {
        val api = FakeApi(fetchResults = ArrayDeque(listOf(fetchResult(200, listOf(segment("u1"))))))
        val repository = SponsorBlockRepository(api, cacheTtlMs = 60_000L)

        repository.fetchAndCache(SponsorBlockQuery("BV17x411w7KC", 1L))

        assertNotNull(repository.getCached(SponsorBlockQuery("BV17x411w7KC", 2L)))
        assertEquals(1, repository.cacheSize)
        assertEquals(1, api.fetchCalls.size)
    }

    /** 缓存条目上限:超过上限时按最旧淘汰,不会随观看量单调增长。 */
    @Test
    fun evictsOldestEntriesWhenCacheExceedsCapacity() {
        val clock = FakeClock()
        val api = FakeApi()
        val repository = SponsorBlockRepository(api, cacheTtlMs = 24L * 60L * 60_000L, nowMs = clock::now)

        for (index in 0 until 40) {
            clock.advanceBy(1L)
            val query = SponsorBlockQuery("BV1test0000$index", index.toLong())
            repository.fetchAndCache(query)
            assertTrue("cache grew past capacity at index=$index", repository.cacheSize <= 32)
        }

        // 保留的是最近写入的一批。
        assertNotNull(repository.getCached(SponsorBlockQuery("BV1test000039", 39L)))
        assertNull(repository.getCached(SponsorBlockQuery("BV1test00000", 0L)))
    }

    /** 写入新条目时顺带清理已过期条目。 */
    @Test
    fun purgesExpiredEntriesOnWrite() {
        val clock = FakeClock()
        val api = FakeApi()
        val repository = SponsorBlockRepository(api, cacheTtlMs = 100L, nowMs = clock::now)

        for (index in 0 until 10) {
            repository.fetchAndCache(SponsorBlockQuery("BV1test0000$index", index.toLong()))
        }
        assertEquals(10, repository.cacheSize)

        clock.advanceBy(1_000L)
        repository.fetchAndCache(SponsorBlockQuery("BV1test000999", 999L))

        // 10 条过期 + 1 条新写入。
        assertEquals(1, repository.cacheSize)
    }

    private fun fetchResult(
        statusCode: Int,
        segments: List<SponsorSegment>,
    ) = SponsorBlockClient.FetchResult(statusCode, null, segments)

    private fun segment(uuid: String) = SponsorSegment(
        category = "sponsor",
        actionType = "skip",
        segment = longArrayOf(0L, 1_000L),
        uuid = uuid,
        videoDuration = 0.0,
        locked = false,
        votes = 0L,
    )

    private class FakeClock {
        private var current = 1L
        fun now(): Long = current
        fun advanceBy(deltaMs: Long) {
            current += deltaMs
        }
    }

    private class FakeApi(
        val fetchResults: ArrayDeque<SponsorBlockClient.FetchResult> = ArrayDeque(),
        private val submitResult: SponsorBlockClient.SubmitResult = SponsorBlockClient.SubmitResult(200, null),
    ) : SponsorBlockApi {
        val fetchCalls = mutableListOf<Pair<SponsorBlockQuery, Boolean>>()
        val submitCalls = mutableListOf<Pair<SponsorBlockSubmission, Boolean>>()

        override fun fetchSkipSegments(
            query: SponsorBlockQuery,
            ignoreCache: Boolean,
        ): SponsorBlockClient.FetchResult {
            fetchCalls += query to ignoreCache
            // 队列里的预设结果用完后，返回一个空的 200 结果：
            // 容量/过期这类用例只关心缓存行为，不需要为每次拉取都准备数据。
            return fetchResults.removeFirstOrNull()
                ?: SponsorBlockClient.FetchResult(200, null, emptyList())
        }

        override fun submitSegment(
            submission: SponsorBlockSubmission,
            ignoreCache: Boolean,
        ): SponsorBlockClient.SubmitResult {
            submitCalls += submission to ignoreCache
            return submitResult
        }
    }
}
