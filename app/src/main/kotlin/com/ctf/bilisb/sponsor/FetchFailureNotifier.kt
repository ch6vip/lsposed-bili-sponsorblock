package com.ctf.bilisb.sponsor

import java.util.concurrent.ConcurrentHashMap

/**
 * 片段拉取失败的「状态迁移」通知判定（T5 错误可见化，纯 JVM 可单测）。
 *
 * 为什么按迁移而非每次失败：拉取带 30s 冷却自动重试，断网期间每次重试失败都会
 * 到达这里——逐次 Toast 会刷屏。只在 **成功 → 失败** 的迁移沿上通知一次，
 * 持续失败静默（日志有），恢复成功后重置，下一轮失败再通知。
 *
 * 按 bvid 分桶（换视频各自迁移）；桶数软上限防无界增长。
 */
class FetchFailureNotifier(private val maxTracked: Int = 32) {

    private val lastOkByBvid = ConcurrentHashMap<String, Boolean>()

    /**
     * 通报一次拉取结果。返回 true 表示「这次应通知用户」：
     * 即该 bvid 从成功态（或首次出现）进入失败态。
     */
    fun shouldNotifyOnFailure(bvid: String, ok: Boolean): Boolean {
        if (ok) {
            lastOkByBvid[bvid] = true
            return false
        }
        if (lastOkByBvid.size > maxTracked) {
            lastOkByBvid.clear()
        }
        // putIfAbsent 返回 null = 首次出现（此前无成功记录）——首次就失败也通知，
        // 但只此一次；已被记录为失败的（value=false）不再重复通知。
        val previous = lastOkByBvid.putIfAbsent(bvid, false)
        return previous == null || previous
    }
}
