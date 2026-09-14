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

    // ------------------------------------------------------------------ 边界

    /** lookahead:position 恰好等于 start - 250 时应当命中(半开区间左端为闭)。 */
    @Test
    fun hitsExactlyAtLookaheadBoundary() {
        val target = segment("sponsor", "skip", 10_000L, 12_000L, "s1")

        assertEquals("s1", SkipDecision.findActiveSkipSegment(9_750L, listOf(target))?.uuid)
        assertNull(SkipDecision.findActiveSkipSegment(9_749L, listOf(target)))
    }

    /** 半开区间:position 恰好等于 endMs 时片段已经走完,不能再命中(否则会往回跳)。 */
    @Test
    fun doesNotHitAtSegmentEnd() {
        val target = segment("sponsor", "skip", 10_000L, 12_000L, "s1")

        assertNull(SkipDecision.findActiveSkipSegment(12_000L, listOf(target)))
        assertEquals("s1", SkipDecision.findActiveSkipSegment(11_999L, listOf(target))?.uuid)
    }

    /** mute 片段同样不能在 endMs 处命中。 */
    @Test
    fun muteDoesNotHitAtSegmentEnd() {
        val mute = segment("intro", "mute", 10_000L, 12_000L, "m1")

        assertNull(SkipDecision.findActiveMuteSegment(12_000L, listOf(mute)))
    }

    /** 零长片段(endMs == startMs)与逆序片段(endMs < startMs)一律丢弃。 */
    @Test
    fun ignoresZeroLengthAndInvertedSegments() {
        val zeroLength = segment("sponsor", "skip", 10_000L, 10_000L, "zero")
        val inverted = segment("sponsor", "skip", 10_000L, 9_000L, "inverted")

        assertNull(SkipDecision.findActiveSkipSegment(10_000L, listOf(zeroLength, inverted)))
        assertNull(SkipDecision.findActiveMuteSegment(10_000L, listOf(zeroLength, inverted)))
    }

    /** 起点为负的片段是脏数据,直接丢弃。 */
    @Test
    fun ignoresNegativeStartSegments() {
        val negative = segment("sponsor", "skip", -5_000L, 1_000L, "neg")

        assertNull(SkipDecision.findActiveSkipSegment(0L, listOf(negative)))
    }

    /** 时长未知(durationMs <= 0)时不做任何判定,避免用不可信的位置误跳。 */
    @Test
    fun returnsNullWhenDurationUnknown() {
        val target = segment("sponsor", "skip", 10_000L, 12_000L, "s1")

        assertNull(SkipDecision.findActiveSkipSegment(10_000L, listOf(target), durationMs = 0L))
        assertNull(SkipDecision.findActiveMuteSegment(10_000L, listOf(target), durationMs = 0L))
    }

    /** position 越界(负 / 超过总时长)时先夹到 [0, durationMs] 再判定,不因夹取产生误命中。 */
    @Test
    fun clampsOutOfRangePositionBeforeDeciding() {
        val target = segment("sponsor", "skip", 10_000L, 12_000L, "s1")

        // 夹到 durationMs = 12_000 → 恰好等于 endMs,不命中。
        assertNull(SkipDecision.findActiveSkipSegment(99_000L, listOf(target), durationMs = 12_000L))
        // 夹到 0 → 片段在 10_000,不命中。
        assertNull(SkipDecision.findActiveSkipSegment(-5_000L, listOf(target), durationMs = 12_000L))
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
