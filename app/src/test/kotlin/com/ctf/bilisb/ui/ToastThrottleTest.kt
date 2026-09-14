package com.ctf.bilisb.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ToastThrottle] 的单测：相同文案 1 秒内只弹一次。
 *
 * 时间由调用方注入，纯 JVM 可跑（不需要 Robolectric / SystemClock）。
 */
class ToastThrottleTest {
    private val window = 1_000L

    @Test
    fun allowsFirstMessageAndRejectsDuplicateWithinWindow() {
        val throttle = ToastThrottle(window)

        assertTrue(throttle.shouldShow("跳过: 赞助/恰饭", nowMs = 10_000L))
        assertFalse(throttle.shouldShow("跳过: 赞助/恰饭", nowMs = 10_100L))
        assertFalse(throttle.shouldShow("跳过: 赞助/恰饭", nowMs = 10_999L))
    }

    @Test
    fun allowsAgainAfterWindow() {
        val throttle = ToastThrottle(window)
        assertTrue(throttle.shouldShow("标记 精彩时刻", nowMs = 0L))

        assertTrue(throttle.shouldShow("标记 精彩时刻", nowMs = 1_000L))
        assertFalse(throttle.shouldShow("标记 精彩时刻", nowMs = 1_500L))
        assertTrue(throttle.shouldShow("标记 精彩时刻", nowMs = 2_000L))
    }

    @Test
    fun highFrequencyCallsDoNotPushTheWindowForward() {
        val throttle = ToastThrottle(window)
        assertTrue(throttle.shouldShow("已取消标记", nowMs = 0L))

        // 每 100ms 调一次：若被节流时错误地刷新了时间戳，窗口会被无限推迟
        var now = 100L
        while (now < 1_000L) {
            assertFalse(throttle.shouldShow("已取消标记", nowMs = now))
            now += 100L
        }
        assertTrue(throttle.shouldShow("已取消标记", nowMs = 1_000L))
    }

    @Test
    fun throttlesMessagesIndependently() {
        val throttle = ToastThrottle(window)
        assertTrue(throttle.shouldShow("A", nowMs = 0L))
        assertTrue(throttle.shouldShow("B", nowMs = 0L))
        assertFalse(throttle.shouldShow("A", nowMs = 500L))
        assertFalse(throttle.shouldShow("B", nowMs = 500L))
        assertTrue(throttle.shouldShow("A", nowMs = 1_000L))
    }
}
