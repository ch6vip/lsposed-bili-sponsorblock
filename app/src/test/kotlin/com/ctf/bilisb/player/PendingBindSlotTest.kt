package com.ctf.bilisb.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PendingBindSlot] 的语义回归：deferred bind 挂起槽的 CAS 行为收在这个纯 JVM 类里，
 * 「谁消费了 pending」「teardown 会不会误清新页的 pending」「挂了多久」都能脱离宿主直接测。
 *
 * 背景：6.6.0 上补绑的两个宿主触发器在正常播放期间都是死的，pending 曾挂死整个会话
 * （2026-09-30 真机 4.7 分钟静默零跳过）。修复后补绑由自持轮询驱动，这个槽是两者之间的状态机。
 */
class PendingBindSlotTest {

    private class Widget

    /** 可控时钟：年龄语义（sinceMs 不因放回而重置）要靠它钉死。 */
    private class FakeClock(var nowMs: Long = 1_000L)

    private fun slotWith(clock: FakeClock) = PendingBindSlot { clock.nowMs }

    @Test
    fun `set 记录 contextHash 与 host 并盖上当时的时间戳`() {
        val clock = FakeClock(nowMs = 1_234)
        val slot = slotWith(clock)
        val host = Widget()

        slot.set(7, host, host)

        val pending = slot.peek()
        assertNotNull(pending)
        assertEquals(7, pending!!.contextHash)
        assertSame(host, pending.host)
        assertEquals(1_234, pending.sinceMs)
    }

    @Test
    fun `consumeAny 拿走并清空槽`() {
        val slot = PendingBindSlot { 0 }
        val host = Widget()
        slot.set(1, host, host)

        val taken = slot.consumeAny()

        assertNotNull(taken)
        assertEquals(1, taken!!.contextHash)
        assertNull("消费后槽应为空", slot.peek())
    }

    @Test
    fun `consumeMatching 只消费匹配 context 的 pending`() {
        val slot = PendingBindSlot { 0 }
        val host = Widget()
        slot.set(9, host, host)

        assertNull("别人的 context 不应消费到", slot.consumeMatching(8))
        assertNotNull("槽里还应留着", slot.peekFor(9))

        val taken = slot.consumeMatching(9)
        assertNotNull(taken)
        assertNull(slot.peek())
    }

    @Test
    fun `restore 只在槽为空时成功且不重置时间戳`() {
        val clock = FakeClock(nowMs = 5_000)
        val slot = slotWith(clock)
        val host = Widget()
        slot.set(3, host, host)
        val taken = slot.consumeAny()!!

        clock.nowMs = 99_999 // 时钟大幅前进：放回不得重置 sinceMs
        assertTrue("空槽上放回应成功", slot.restore(taken))
        assertEquals("放回必须保留原始时间戳（挂了多久要累计）", 5_000, slot.peek()!!.sinceMs)

        // 槽非空时再放回（另一条路径刚 set 过）必须失败
        slot.consumeAny()
        slot.set(4, host, host)
        assertFalse("槽已被占用时 restore 应失败", slot.restore(taken))
        assertEquals(4, slot.peek()!!.contextHash)
    }

    @Test
    fun `clearIfHost 按对象身份清 不误伤别的 widget 的 pending`() {
        val slot = PendingBindSlot { 0 }
        val oldHost = Widget()
        val newHost = Widget()
        slot.set(1, newHost, newHost) // 新页已挂起自己的 pending

        assertFalse("旧 widget 的 teardown 不得清新页的 pending", slot.clearIfHost(oldHost))
        assertNotNull("新页的 pending 应完好", slot.peekFor(1))

        assertTrue("本 widget 的 teardown 应清掉自己的 pending", slot.clearIfHost(newHost))
        assertNull(slot.peek())
    }

    @Test
    fun `clearIfHost 用同一性而非相等性比较`() {
        val slot = PendingBindSlot { 0 }
        // equals 相同但 === 不同的两个对象：clearIfHost 必须看身份
        val host = EquatableWidget(1)
        val impostor = EquatableWidget(1)
        assertTrue("前置：两对象应 equals 相等", host == impostor)
        assertFalse("前置：两对象应不同身份", host === impostor)
        slot.set(1, host, host)

        assertFalse(slot.clearIfHost(impostor))
        assertNotNull(slot.peek())
    }

    private data class EquatableWidget(val id: Int)

    @Test
    fun `并发语义 消费赢家唯一 输家 restore 不覆盖新值`() {
        val slot = PendingBindSlot { 0 }
        val host = Widget()
        slot.set(5, host, host)

        // 路径 A（宿主信号 ensureDeferredBind）抢占式消费
        val byHost = slot.consumeAny()
        assertNotNull(byHost)
        // 路径 B（轮询 tick）随后也来消费：必须拿不到
        assertNull(slot.consumeMatching(5))
        // 路径 B 先 set 了新 pending，路径 A 才放回：restore 必须失败，不得覆盖
        val newHost = Widget()
        slot.set(6, newHost, newHost)
        assertFalse(slot.restore(byHost!!))
        assertEquals(6, slot.peek()!!.contextHash)
    }

    @Test
    fun `peekFor 不消费槽`() {
        val slot = PendingBindSlot { 0 }
        val host = Widget()
        slot.set(2, host, host)

        repeat(3) { assertNotNull(slot.peekFor(2)) }
        assertNotNull("peek 之后槽里还得在", slot.peek())
    }
}
