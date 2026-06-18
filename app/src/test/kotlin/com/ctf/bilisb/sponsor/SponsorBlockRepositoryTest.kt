package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorBlockQuery
import com.ctf.bilisb.model.SponsorBlockSubmission
import com.ctf.bilisb.model.SponsorSegment
import com.ctf.bilisb.net.SponsorBlockApi
import com.ctf.bilisb.net.SponsorBlockClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        repository.fetchAndCache(SponsorBlockQuery("BV17x411w7KC", 123L))

        assertNull(repository.getCached(SponsorBlockQuery("BV17x411w7KC", 123L)))
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
            return fetchResults.removeFirstOrNull()
                ?: error("No more fetch results configured")
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
