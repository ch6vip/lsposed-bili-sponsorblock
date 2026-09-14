package com.ctf.bilisb.ui

import com.ctf.bilisb.model.SponsorSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RemainingTimeFormatterTest {
    @Test
    fun mergesOverlappingSkipSegmentsAndIgnoresHighlights() {
        val adjusted = RemainingTimeFormatter.adjustedDuration(
            durationMs = 60_000L,
            segments = listOf(
                segment("sponsor", "skip", 10_000L, 20_000L),
                segment("intro", "skip", 18_000L, 25_000L),
                segment("poi_highlight", "skip", 30_000L, 35_000L),
            ),
        )

        assertEquals(45_000L, adjusted)
    }

    @Test
    fun filtersSegmentsShorterThanMinSkipDuration() {
        val segments = listOf(
            segment("sponsor", "skip", 10_000L, 20_000L),   // 10s：不满足阈值
            segment("intro", "skip", 30_000L, 40_000L),     // 10s：不满足阈值
        )

        // 阈值 15s：两段都太短，实际不会跳过 → 剩余时长不变
        assertEquals(
            300_000L,
            RemainingTimeFormatter.adjustedDuration(300_000L, segments, minSkipDurationMs = 15_000L),
        )
        // 阈值 10s：两段都达标，扣满 20s
        assertEquals(
            280_000L,
            RemainingTimeFormatter.adjustedDuration(300_000L, segments, minSkipDurationMs = 10_000L),
        )
    }

    @Test
    fun keepsDurationWhenSkipIsDisabled() {
        val segments = listOf(segment("sponsor", "skip", 10_000L, 20_000L))

        assertEquals(
            300_000L,
            RemainingTimeFormatter.adjustedDuration(300_000L, segments, skipEnabled = false),
        )
        // 关闭跳过时连阈值也不该起作用
        assertEquals(
            300_000L,
            RemainingTimeFormatter.adjustedDuration(
                300_000L,
                segments,
                minSkipDurationMs = 1_000L,
                skipEnabled = false,
            ),
        )
    }

    @Test
    fun returnsDurationUnchangedForNonPositiveDuration() {
        val segments = listOf(segment("sponsor", "skip", 10_000L, 20_000L))

        assertEquals(0L, RemainingTimeFormatter.adjustedDuration(0L, segments))
        assertEquals(-5_000L, RemainingTimeFormatter.adjustedDuration(-5_000L, segments))
        assertEquals(
            -5_000L,
            RemainingTimeFormatter.adjustedDuration(-5_000L, segments, minSkipDurationMs = 1_000L),
        )
    }

    @Test
    fun doesNotDeductBeyondDuration() {
        val segments = listOf(segment("sponsor", "skip", 0L, 999_999L))

        assertEquals(0L, RemainingTimeFormatter.adjustedDuration(60_000L, segments))
    }

    @Test
    fun appendsFormattedAdjustedDuration() {
        val decorated = RemainingTimeFormatter.appendAdjustedDuration("00:16 / 30:01", 905_000L)

        assertEquals("00:16 / 30:01 (15:05)", decorated.toString())
    }

    @Test
    fun appendsHourFormatForLongAdjustedDuration() {
        // 3661 秒 → 1:01:01
        val decorated = RemainingTimeFormatter.appendAdjustedDuration("10:00 / 2:00:00", 3_661_000L)

        assertEquals("10:00 / 2:00:00 (1:01:01)", decorated.toString())
    }

    @Test
    fun appendsHourFormatFromRealFormattedDuration() {
        val segments = listOf(segment("sponsor", "skip", 0L, 3_661_000L))
        val adjusted = RemainingTimeFormatter.adjustedDuration(7_322_000L, segments)

        assertEquals(3_661_000L, adjusted)
        assertEquals(
            "00:00 / 2:02:02 (1:01:01)",
            RemainingTimeFormatter.appendAdjustedDuration("00:00 / 2:02:02", adjusted).toString(),
        )
    }

    @Test
    fun returnsOriginalTextWhenNothingToAppend() {
        val original = "00:16 / 30:01"

        assertSame(original, RemainingTimeFormatter.appendAdjustedDuration(original, 0L))
        assertSame(original, RemainingTimeFormatter.appendAdjustedDuration(original, -1L))
    }

    @Test
    fun formatsMinutesBoundaryAndRoundUp() {
        // 3599s 还没到小时：59:59
        assertEquals(
            "00:00 / 1:00:00 (59:59)",
            RemainingTimeFormatter.appendAdjustedDuration("00:00 / 1:00:00", 3_599_000L).toString(),
        )
        // 3600s 整：1:00:00
        assertEquals(
            "00:00 / 1:00:00 (1:00:00)",
            RemainingTimeFormatter.appendAdjustedDuration("00:00 / 1:00:00", 3_600_000L).toString(),
        )
        // 不足 1 秒向上取整（与旧实现一致）
        assertEquals(
            "00:00 / 1:00:00 (00:01)",
            RemainingTimeFormatter.appendAdjustedDuration("00:00 / 1:00:00", 1L).toString(),
        )
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
