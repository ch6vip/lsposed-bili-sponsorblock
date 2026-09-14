package com.ctf.bilisb.ui

import com.ctf.bilisb.model.SponsorSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarkerGeometry] 的单测。
 *
 * 重点不是"画得好不好看"，而是**任何非法片段都不能抛异常**：
 * 旧实现里 `coerceIn(min, max)` 在 min > max 时抛 IllegalArgumentException，
 * 被上层 runCatching 吞掉后会中断 forEach，使该片段及其之后的标记整帧消失。
 */
class MarkerGeometryTest {
    private val epsilon = 0.001f

    @Test
    fun mapsSegmentTimeToPixelRange() {
        val ranges = MarkerGeometry.markerRanges(
            boundsLeft = 100f,
            boundsRight = 1900f,
            durationMs = 1_800_000L,
            segments = listOf(segment("sponsor", "skip", 300_000L, 600_000L)),
        )

        assertEquals(1, ranges.size)
        val range = ranges.single()
        // 100 + 300s/1800s * 1800px = 400
        assertEquals(400f, range.xStart, epsilon)
        // 100 + 600s/1800s * 1800px = 700
        assertEquals(700f, range.xEnd, epsilon)
        assertEquals("sponsor", range.category)
        assertTrue(!range.isPoi)
    }

    @Test
    fun skipsSegmentStartingAfterDurationWithoutThrowing() {
        val ranges = MarkerGeometry.markerRanges(
            boundsLeft = 100f,
            boundsRight = 1900f,
            durationMs = 1_800_000L,
            segments = listOf(segment("sponsor", "skip", 2_000_000L, 2_100_000L)),
        )

        assertTrue(ranges.isEmpty())
    }

    @Test
    fun skipsOnlyBadSegmentAndKeepsOthers() {
        val ranges = MarkerGeometry.markerRanges(
            boundsLeft = 100f,
            boundsRight = 1900f,
            durationMs = 1_800_000L,
            segments = listOf(
                segment("sponsor", "skip", 2_000_000L, 2_100_000L), // 起点超出时长
                segment("intro", "skip", 900_000L, 1_000_000L),    // 正常
                segment("sponsor", "skip", 0L, 0L),                // end == start
            ),
        )

        // 坏片段被跳过，但不影响后面的片段（旧实现会在这里整帧消失）
        assertEquals(2, ranges.size)
        assertEquals("intro", ranges[0].category)
        assertEquals("sponsor", ranges[1].category)
    }

    @Test
    fun clampsSegmentEndingPastTheRightEdge() {
        val ranges = MarkerGeometry.markerRanges(
            boundsLeft = 100f,
            boundsRight = 1900f,
            durationMs = 1_800_000L,
            segments = listOf(segment("outro", "skip", 1_700_000L, 1_900_000L)),
        )

        val range = ranges.single()
        assertEquals(1900f, range.xEnd, epsilon)
        assertTrue(range.xStart <= range.xEnd)
    }

    @Test
    fun enforcesMinimumOnePixelWidthNearRightEdge() {
        // 片段尾部离结束只剩 0.5px：xStart + 1f > boundsRight
        val ranges = MarkerGeometry.markerRanges(
            boundsLeft = 0f,
            boundsRight = 100f,
            durationMs = 200_000L,
            segments = listOf(segment("filler", "skip", 199_999L, 200_000L)),
        )

        val range = ranges.single()
        assertEquals(100f, range.xStart, epsilon)
        assertEquals(100f, range.xEnd, epsilon)
    }

    @Test
    fun givesShortSegmentOnePixelWidth() {
        val ranges = MarkerGeometry.markerRanges(
            boundsLeft = 0f,
            boundsRight = 1800f,
            durationMs = 1_800_000L,
            segments = listOf(segment("filler", "skip", 500_000L, 500_100L)),
        )

        val range = ranges.single()
        assertEquals(500f, range.xStart, epsilon)
        assertEquals(501f, range.xEnd, epsilon)
    }

    @Test
    fun returnsEmptyForNonPositiveDuration() {
        val segment = segment("sponsor", "skip", 0L, 10_000L)

        assertTrue(MarkerGeometry.markerRanges(0f, 100f, 0L, listOf(segment)).isEmpty())
        assertTrue(MarkerGeometry.markerRanges(0f, 100f, -1L, listOf(segment)).isEmpty())
    }

    @Test
    fun returnsEmptyForDegenerateBoundsOrEmptySegments() {
        val segment = segment("sponsor", "skip", 0L, 10_000L)

        assertTrue(MarkerGeometry.markerRanges(100f, 100f, 1000L, listOf(segment)).isEmpty())
        assertTrue(MarkerGeometry.markerRanges(200f, 100f, 1000L, listOf(segment)).isEmpty())
        assertTrue(MarkerGeometry.markerRanges(0f, 100f, 1000L, emptyList()).isEmpty())
    }

    @Test
    fun marksActionTypePoiAsCircle() {
        val ranges = MarkerGeometry.markerRanges(
            boundsLeft = 100f,
            boundsRight = 1900f,
            durationMs = 1_800_000L,
            segments = listOf(segment("poi_highlight", "poi", 600_000L, 700_000L)),
        )

        val range = ranges.single()
        assertTrue(range.isPoi)
        // POI 只给圆心，xEnd 与 xStart 相同
        assertEquals(700f, range.xStart, epsilon)
        assertEquals(range.xStart, range.xEnd, epsilon)
    }

    @Test
    fun marksPoiHighlightCategoryAsCircle() {
        // 分类是高亮但 actionType 不是 poi 时同样按圆点画（对齐 APK 的 an.d() 分支）
        val ranges = MarkerGeometry.markerRanges(
            boundsLeft = 0f,
            boundsRight = 1000f,
            durationMs = 100_000L,
            segments = listOf(segment("poi_highlight", "skip", 50_000L, 60_000L)),
        )

        assertTrue(ranges.single().isPoi)
    }

    @Test
    fun skipsSegmentsWithReversedOrMissingRange() {
        val ranges = MarkerGeometry.markerRanges(
            boundsLeft = 0f,
            boundsRight = 1000f,
            durationMs = 100_000L,
            segments = listOf(
                segment("sponsor", "skip", 60_000L, 30_000L),   // end < start：必须跳过
                SponsorSegment(
                    category = "intro",
                    actionType = "skip",
                    segment = longArrayOf(10_000L),            // 只有一个元素，endMs 回退为 startMs
                    uuid = "intro-only-start",
                    videoDuration = 0.0,
                    locked = false,
                    votes = 0L,
                ),
                segment("outro", "skip", 80_000L, 90_000L),     // 正常
            ),
        )

        // end < start 被安全跳过（不再抛异常）；只剩 1 个元素的片段按 1px 宽画出（极短片段也要可见）；
        // 正常片段保留 —— 关键是被跳过的片段不会中断后面的片段。
        assertEquals(2, ranges.size)
        assertEquals("intro", ranges[0].category)
        assertEquals(100f, ranges[0].xStart, epsilon)
        assertEquals(1f, ranges[0].xEnd - ranges[0].xStart, epsilon)
        assertEquals("outro", ranges[1].category)
    }

    @Test
    fun clampsNegativeStartToLeftEdge() {
        val ranges = MarkerGeometry.markerRanges(
            boundsLeft = 100f,
            boundsRight = 1900f,
            durationMs = 1_800_000L,
            segments = listOf(segment("sponsor", "skip", -5_000L, 90_000L)),
        )

        val range = ranges.single()
        assertEquals(100f, range.xStart, epsilon)
        assertEquals(190f, range.xEnd, epsilon)
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
        uuid = "$category-$startMs-$endMs",
        videoDuration = 0.0,
        locked = false,
        votes = 0L,
    )
}
