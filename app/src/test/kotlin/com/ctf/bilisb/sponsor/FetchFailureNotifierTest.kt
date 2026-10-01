package com.ctf.bilisb.sponsor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FetchFailureNotifier] 的回归（T5 错误可见化）：
 * 只在「成功 → 失败」的迁移沿上通知；持续失败静默；恢复后重置；按 bvid 分桶。
 *
 * 背景：拉取带 30s 冷却自动重试，断网期间每次重试失败都到达这里——
 * 逐次通知会刷屏（节流窗只有 1s，挡不住 30s 间隔的重复）。
 */
class FetchFailureNotifierTest {

    @Test
    fun `首次失败通知 持续失败静默`() {
        val n = FetchFailureNotifier()
        assertTrue("首次失败应通知", n.shouldNotifyOnFailure("bv1", false))
        assertFalse("持续失败不再通知", n.shouldNotifyOnFailure("bv1", false))
        assertFalse("继续静默", n.shouldNotifyOnFailure("bv1", false))
    }

    @Test
    fun `恢复成功后 再次失败重新通知`() {
        val n = FetchFailureNotifier()
        assertTrue(n.shouldNotifyOnFailure("bv1", false))
        n.shouldNotifyOnFailure("bv1", true)  // 恢复
        assertTrue("恢复后的新失败应再通知", n.shouldNotifyOnFailure("bv1", false))
    }

    @Test
    fun `成功路径永不通知且重置状态`() {
        val n = FetchFailureNotifier()
        assertFalse(n.shouldNotifyOnFailure("bv1", true))
        assertFalse(n.shouldNotifyOnFailure("bv1", true))
    }

    @Test
    fun `按 bvid 分桶 互不影响`() {
        val n = FetchFailureNotifier()
        assertTrue(n.shouldNotifyOnFailure("bvA", false))
        // bvB 首次失败独立通知；bvA 持续失败仍静默
        assertTrue(n.shouldNotifyOnFailure("bvB", false))
        assertFalse(n.shouldNotifyOnFailure("bvA", false))
        assertFalse(n.shouldNotifyOnFailure("bvB", false))
    }

    @Test
    fun `先成功后失败的迁移沿才通知`() {
        val n = FetchFailureNotifier()
        n.shouldNotifyOnFailure("bv1", true)
        assertTrue("成功→失败迁移沿应通知", n.shouldNotifyOnFailure("bv1", false))
    }

    @Test
    fun `桶数超软上限时清空重来 不无限增长`() {
        val n = FetchFailureNotifier(maxTracked = 4)
        for (i in 1..6) n.shouldNotifyOnFailure("bv$i", false)
        // 清空后 bv1 视为首次出现 → 再通知一次（可接受的代价，见类注释）
        assertTrue(n.shouldNotifyOnFailure("bv7", false))
    }
}
