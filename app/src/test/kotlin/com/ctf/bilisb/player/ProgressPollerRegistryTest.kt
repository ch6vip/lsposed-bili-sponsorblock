package com.ctf.bilisb.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * [ProgressPollerRegistry] 的回归测试。
 *
 * 存在的理由：6.6.0 的自动跳过**完全依赖**这张表的轮询任务（宿主没有任何可依赖的进度 tick），
 * 表一旦失同步，现象就是「日志安静地不再跳过」，而真机排查时这与「服务端拉不到片段」
 * 完全无法区分（2026-09-29 花了整晚才定位到同类问题）。
 *
 * 这里用**真实定时器**（间隔 10–20ms）跑，不 mock 调度：要验证的正是「任务会不会真的停/真的被挡」。
 */
class ProgressPollerRegistryTest {

    /** 等一次 tick（最多 2s）。任务体每被调用一次就放行一个许可。 */
    private fun awaitTick(latch: CountDownLatch): Boolean =
        latch.await(2, TimeUnit.SECONDS)

    /** 轮询断言：等到 [condition] 成立或超时。比「睡固定时长再断言」稳定得多。 */
    private fun awaitCondition(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun `第一次 start 会真的跑起来`() {
        val registry = ProgressPollerRegistry(intervalMs = 10)
        val runs = AtomicLong(0)

        registry.start(1) {
            runs.incrementAndGet()
            false
        }

        assertTrue(
            "任务应被反复调度（实际跑了 ${runs.get()} 次）",
            awaitCondition { runs.get() >= 3 },
        )
        assertTrue("任务在表里应显示为运行中", registry.isRunning(1))
        registry.stopAll()
        assertFalse(registry.isRunning(1))
        assertEquals(0, registry.size())
    }

    /** 幂等：同一 context 重复 start 不能跑出第二条轮询。 */
    @Test
    fun `重复 start 是幂等的`() {
        val registry = ProgressPollerRegistry(intervalMs = 10)
        val runs = AtomicInteger(0)
        val once = CountDownLatch(1)

        registry.start(7) {
            runs.incrementAndGet()
            once.countDown()
            false
        }
        assertTrue(awaitTick(once))
        repeat(20) { registry.start(7) { runs.incrementAndGet(); false } }

        // 若重复 start 装了第二条任务，同窗口内计数会明显翻倍；这里给足一个完整周期再看。
        Thread.sleep(60)
        assertEquals("同一 context 只应存在一条登记", 1, registry.size())
        assertTrue(registry.isRunning(7))
        registry.stopAll()
        assertEquals(0, registry.size())
    }

    /** stop 之后必须真的不再执行（取消生效），并且表项被摘掉。 */
    @Test
    fun `stop 之后任务不再执行`() {
        val registry = ProgressPollerRegistry(intervalMs = 10)
        val runs = AtomicLong(0)

        registry.start(2) {
            runs.incrementAndGet()
            false
        }
        assertTrue("任务应先跑起来", awaitCondition { runs.get() >= 3 })
        registry.stop(2)
        assertFalse(registry.isRunning(2))

        val afterStop = runs.get()
        Thread.sleep(80)
        assertEquals("stop 之后不应再有任何 tick", afterStop, runs.get())
    }

    /** 关键回归：任务自停（tick 返回 true）后，再次 start 必须能重新跑起来。
     *  旧实现把表项留成「已取消但仍在表里」，后续 start 被 containsKey 静默挡掉 → 永久零跳过。 */
    @Test
    fun `自停之后可以重新 start`() {
        val registry = ProgressPollerRegistry(intervalMs = 10)
        val first = CountDownLatch(1)

        registry.start(3) {
            first.countDown()
            true // 自停
        }
        assertTrue("第一次应跑到", awaitTick(first))

        // 等自停生效（任务自己摘表）
        val deadline = System.currentTimeMillis() + 2_000
        while (registry.isRunning(3) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse("自停后不应仍标记为运行中", registry.isRunning(3))

        // 重新起表：必须真的跑（这是「静默零跳过」的回归点）
        val second = CountDownLatch(2)
        registry.start(3) {
            second.countDown()
            false
        }
        assertTrue("自停后重新 start 必须能跑起来", awaitTick(second))
        assertTrue(registry.isRunning(3))
        registry.stopAll()
    }

    /** 任务体抛异常不能杀死周期任务，且异常要交给 onError。 */
    @Test
    fun `tick 抛异常不影响后续 tick 且上报 onError`() {
        val errors = AtomicInteger(0)
        val registry = ProgressPollerRegistry(intervalMs = 10, onError = { errors.incrementAndGet() })
        val runs = AtomicLong(0)
        val latch = CountDownLatch(4)

        registry.start(4) {
            runs.incrementAndGet()
            latch.countDown()
            if (runs.get() == 2L) error("boom")
            false
        }

        assertTrue("异常后任务应继续被调度", awaitTick(latch))
        assertTrue("异常次数应被上报", errors.get() >= 1)
        assertTrue(registry.isRunning(4))
        registry.stopAll()
    }

    /** 自停与「重新 start」交错时不能出现「表项在、但没人跑」。 */
    @Test
    fun `自停与重复 start 交错不会留下死表项`() {
        val registry = ProgressPollerRegistry(intervalMs = 5)
        val ticks = ConcurrentHashMap<Int, AtomicInteger>()
        val stopAt = 3

        // 每个任务跑 3 次就自停；主线程同时不停 start，模拟「handle 消失 + 立刻补绑」的交错。
        val keys = (1..8).toList()
        val firstRound = CountDownLatch(keys.size * stopAt)
        keys.forEach { key ->
            ticks[key] = AtomicInteger(0)
            registry.start(key) {
                val n = ticks.getValue(key).incrementAndGet()
                firstRound.countDown()
                n >= stopAt
            }
        }
        assertTrue("首轮任务应跑满", awaitTick(firstRound))

        // 首轮自停后全部重新起表，并断言每一个都真的在跑
        val secondRound = CountDownLatch(keys.size * 2)
        keys.forEach { key ->
            registry.start(key) {
                secondRound.countDown()
                false
            }
        }
        assertTrue("重新起表后每个 key 都应有活任务", awaitTick(secondRound))
        keys.forEach { key ->
            assertTrue("key=$key 重新起表后应处于运行中", registry.isRunning(key))
        }
        registry.stopAll()
    }

    // ------------------------------------------------------------------
    // deferred bind 与轮询的协作契约（progressPollerTick 的判定形状，纯 JVM 复刻）。
    // 背景：6.6.0 上补绑的两个宿主触发器在正常播放期间都是死的，挂起的 pending 若被
    // 「无 handle ⇒ 自停」误杀，就是整个会话静默零跳过（2026-09-30 真机 4.7 分钟）。
    // ------------------------------------------------------------------

    @Test
    fun `无 handle 但有 pending 时轮询必须活着 不能被第一条 tick 误杀`() {
        val registry = ProgressPollerRegistry(intervalMs = 10)
        val hasHandle = AtomicBoolean(false)
        val pending = AtomicBoolean(true)
        val ticks = AtomicInteger(0)

        registry.start(11) {
            ticks.incrementAndGet()
            // 复刻 progressPollerTick 的返回值（true=自停）：停止 ⟺ 无 handle 且无 pending
            !hasHandle.get() && !pending.get()
        }

        // 给足多条 tick 的窗口：若自停判定有误（无 handle 即停），这里只会跑到 1
        assertTrue(
            "pending 存续期间 tick 应持续发生（实际 ${ticks.get()} 次）",
            awaitCondition { ticks.get() >= 5 },
        )
        assertTrue(registry.isRunning(11))
        registry.stopAll()
    }

    @Test
    fun `pending 清除后 无 handle 的轮询按原语义自停`() {
        val registry = ProgressPollerRegistry(intervalMs = 10)
        val hasHandle = AtomicBoolean(false)
        val pending = AtomicBoolean(true)
        val ticks = AtomicInteger(0)

        registry.start(12) {
            ticks.incrementAndGet()
            !hasHandle.get() && !pending.get()
        }
        assertTrue(awaitCondition { ticks.get() >= 2 })

        pending.set(false) // 放弃等待（过期/teardown 清掉）
        assertTrue(
            "pending 没了之后任务应自停",
            awaitCondition { !registry.isRunning(12) },
        )
        assertEquals(0, registry.size())
    }

    @Test
    fun `tick 内完成补绑后 同一条任务继续承担喂入 不需要重起表`() {
        val registry = ProgressPollerRegistry(intervalMs = 10)
        val hasHandle = AtomicBoolean(false)
        val pending = AtomicBoolean(true)
        val fed = AtomicInteger(0)

        registry.start(13) {
            // 复刻 tryCompletePendingBindFromTick 的效果：补绑成功 ⇒ handle 就位 ⇒ 本 tick 落到喂入分支
            if (!hasHandle.get() && pending.get()) hasHandle.set(true)
            if (hasHandle.get()) {
                pending.set(false)
                fed.incrementAndGet()
            }
            false // completeBind 里的 startProgressPoller 对活任务幂等，这里也不能停
        }

        assertTrue(
            "补绑完成后应持续喂入（实际喂了 ${fed.get()} 次）",
            awaitCondition { fed.get() >= 5 },
        )
        assertTrue("同一任务应继续运行", registry.isRunning(13))
        registry.stopAll()
    }
}
