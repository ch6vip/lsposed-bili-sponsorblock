package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkipDecisionTest {
    @Test
    fun respectsLookaheadAndSkipsHighlightCategory() {
        val sponsor = segment("sponsor", "skip", 1_000L, 5_000L, "s1")
        val highlight = segment("poi_highlight", "skip", 900L, 4_000L, "h1")

        val active = SkipDecision.findActiveSkipSegment(800L, listOf(highlight, sponsor))

        assertEquals("s1", active?.uuid)
    }

    @Test
    fun filtersShortSegmentsByMinDuration() {
        val shortSegment = segment("sponsor", "skip", 1_000L, 1_500L, "short")

        val active = SkipDecision.findActiveSkipSegment(1_100L, listOf(shortSegment), minDurationMs = 1_000L)

        assertNull(active)
    }

    @Test
    fun findsMuteSegmentsIndependently() {
        val mute = segment("intro", "mute", 2_000L, 4_000L, "m1")

        val active = SkipDecision.findActiveMuteSegment(1_900L, listOf(mute))

        assertEquals("m1", active?.uuid)
    }

    private fun segment(
        category: String,
        actionType: String,
        startMs: Long,
        endMs: Long,
        uuid: String,
    ) = SponsorSegment(
        category = category,
        actionType = actionType,
        segment = longArrayOf(startMs, endMs),
        uuid = uuid,
        videoDuration = 0.0,
        locked = false,
        votes = 0L,
    )
}
