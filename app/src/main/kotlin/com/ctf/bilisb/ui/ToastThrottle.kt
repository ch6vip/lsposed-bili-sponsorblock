package com.ctf.bilisb.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * 相同文案的 Toast 节流。
 *
 * 进度回调 / 倒计时结束可能在同一秒内连着触发多次，宿主 Toast 队列会排成长串。
 * 这里按文案记账：窗口内重复的直接丢弃（调用方不应因此写日志，否则日志比 Toast 还吵）。
 *
 * 纯 Kotlin（时间由调用方注入），因此可以直接 JVM 单测；时间源用
 * `System.currentTimeMillis()` 而不是 `SystemClock`，避免依赖 android.os。
 */
class ToastThrottle(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private val lastShownAt = ConcurrentHashMap<String, Long>()

    /** 同一文案 [windowMs] 内只放行一次。返回 true 表示这次应该弹。 */
    fun shouldShow(message: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val previous = lastShownAt[message]
        if (previous != null && nowMs - previous < windowMs) {
            return false // 被节流：不更新记录，否则窗口起点会被高频调用一直推迟
        }
        if (previous == null) {
            return lastShownAt.putIfAbsent(message, nowMs) == null
        }
        // 窗口已过：只有抢到 CAS 的线程负责刷新记录并放行，其余线程仍然丢弃。
        return lastShownAt.replace(message, previous, nowMs)
    }

    /** 清空记录（目前仅测试使用）。 */
    fun clear() {
        lastShownAt.clear()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 1_000L
    }
}
