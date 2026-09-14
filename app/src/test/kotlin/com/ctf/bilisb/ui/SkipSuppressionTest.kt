package com.ctf.bilisb.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SkipSuppression] 的单测：跳过后的闪回抑制窗口。
 *
 * 真机现象是点了跳过之后按钮/浮层又冒回来，因为 seek 要一两帧才生效，
 * 期间进度回调仍然落在同一片段内。
 */
class SkipSuppressionTest {
    private val window = 2_000L

    @Test
    fun isNotSuppressedBeforeAnySkip() {
        val suppression = SkipSuppression(window)

        assertFalse(suppression.isSuppressed(scopeHash = 1, segmentKey = "a:0-1000", nowMs = 0L))
        assertFalse(suppression.isSuppressed(scopeHash = 1, segmentKey = "a:0-1000", nowMs = 10_000L))
    }

    @Test
    fun suppressesWithinWindowOnly() {
        val suppression = SkipSuppression(window)
        suppression.suppress(1, "a:0-1000", nowMs = 1_000L)

        assertTrue(suppression.isSuppressed(1, "a:0-1000", nowMs = 1_000L))
        assertTrue(suppression.isSuppressed(1, "a:0-1000", nowMs = 2_999L))
        assertFalse(suppression.isSuppressed(1, "a:0-1000", nowMs = 3_000L))
    }

    @Test
    fun reSuppressingExtendsTheWindow() {
        val suppression = SkipSuppression(window)
        suppression.suppress(1, "a:0-1000", nowMs = 1_000L)
        suppression.suppress(1, "a:0-1000", nowMs = 2_500L)

        assertTrue(suppression.isSuppressed(1, "a:0-1000", nowMs = 4_400L))
        assertFalse(suppression.isSuppressed(1, "a:0-1000", nowMs = 4_500L))
    }

    @Test
    fun isolatesDifferentSegmentsAndScopes() {
        val suppression = SkipSuppression(window)
        suppression.suppress(1, "a:0-1000", nowMs = 1_000L)

        assertFalse(suppression.isSuppressed(1, "b:2000-3000", nowMs = 1_100L))
        assertFalse(suppression.isSuppressed(2, "a:0-1000", nowMs = 1_100L))
        assertTrue(suppression.isSuppressed(1, "a:0-1000", nowMs = 1_100L))
    }

    @Test
    fun expiredEntriesAreEvicted() {
        val suppression = SkipSuppression(window)
        suppression.suppress(1, "a:0-1000", nowMs = 1_000L)

        // 窗口过期后同 key 立刻可以被再次抑制（旧记录已被清理，不会误判）
        assertFalse(suppression.isSuppressed(1, "a:0-1000", nowMs = 10_000L))
        suppression.suppress(1, "a:0-1000", nowMs = 10_000L)
        assertTrue(suppression.isSuppressed(1, "a:0-1000", nowMs = 10_500L))
        assertFalse(suppression.isSuppressed(1, "a:0-1000", nowMs = 12_000L))
    }
}
