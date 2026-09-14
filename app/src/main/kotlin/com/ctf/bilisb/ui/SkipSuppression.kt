package com.ctf.bilisb.ui

/**
 * 跳过后的短期抑制窗口。
 *
 * 真机现象：点了「手动跳过 / 倒计时跳过」后按钮会闪回来 —— seek 需要一两帧才生效，
 * 之前的进度回调仍落在片段内，于是又触发一次 show。这里按「上下文 + 片段」记录
 * 跳过时刻，窗口内不再展示，窗口过期后自动回收，避免 Map 无限增长。
 *
 * 纯 Kotlin（时间由调用方注入），因此可以直接 JVM 单测。
 */
class SkipSuppression(private val windowMs: Long) {

    private val suppressedAtByKey = HashMap<String, Long>()

    /** 是否处于抑制窗口内。[nowMs] 用 SystemClock.uptimeMillis()（手动跳过）或 System.currentTimeMillis()。 */
    @Synchronized
    fun isSuppressed(scopeHash: Int, segmentKey: String, nowMs: Long): Boolean {
        evictExpired(nowMs)
        val at = suppressedAtByKey[key(scopeHash, segmentKey)] ?: return false
        return nowMs < at + windowMs
    }

    /** 记录一次跳过；窗口结束时自动失效，无需手工清理。 */
    @Synchronized
    fun suppress(scopeHash: Int, segmentKey: String, nowMs: Long) {
        evictExpired(nowMs)
        suppressedAtByKey[key(scopeHash, segmentKey)] = nowMs
    }

    private fun evictExpired(nowMs: Long) {
        suppressedAtByKey.entries.removeAll { nowMs >= it.value + windowMs }
    }

    private fun key(scopeHash: Int, segmentKey: String): String = "$scopeHash|$segmentKey"
}
