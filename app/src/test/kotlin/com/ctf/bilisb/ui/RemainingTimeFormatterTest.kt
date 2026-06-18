package com.ctf.bilisb.ui

import com.ctf.bilisb.model.SponsorSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class RemainingTimeFormatterTest {
    @Test
    fun mergesOverlappingSkipSegmentsAndIgnoresHighlights() {
        val adjusted = RemainingTimeFormatter.adjustedDuration(
            totalMs = 60_000L,
            segments = listOf(
                segment("sponsor", "skip", 10_000L, 20_000L),
                segment("intro", "skip", 18_000L, 25_000L),
                segment("poi_highlight", "skip", 30_000L, 35_000L),
            ),
        )

        assertEquals(45_000L, adjusted)
    }

    @Test
    fun appendsFormattedAdjustedDuration() {
        val decorated = RemainingTimeFormatter.appendAdjustedDuration("00:16 / 30:01", 905_000L)

        assertEquals("00:16 / 30:01 (15:05)", decorated)
    }

    private fun segment(
        category: String,
        actionType: String,
        startMs: Long,
        endMs: Long,
    ) = SponsorSegment(
        category = category,
        actionType = actionType,
        segment = longArrayOf(startMs, endMs),
        uuid = "$category-$startMs",
        videoDuration = 0.0,
        locked = false,
        votes = 0L,
    )
}
