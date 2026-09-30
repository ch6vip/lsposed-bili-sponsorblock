package com.ctf.bilisb.player

import java.util.concurrent.atomic.AtomicReference

/**
 * 「deferred bind」挂起槽 —— bind 时 core 未就绪的补绑凭据，纯 JVM 类（只有 JDK 依赖）。
 *
 * ## 为什么它需要自己的类
 *
 * `bindPlayerContainer` 触发时 widget 的 core 往往还没注入（真机实测），bind 只能挂起等补绑。
 * 补绑的完成信号曾经只有两个：进度文本控件回调、`seek.v3.g#draw`——**6.6.0 正常播放期间两个
 * 都是死的**（控件不实例化、进度条不逐帧 draw），pending 会挂死整个会话：无 handle ⇒ 无 poller
 * ⇒ 静默零跳过（2026-09-30 真机实测 4.7 分钟），且没有任何探针给它名字。
 *
 * 修复后补绑由自持的 500ms 轮询驱动（见 [ProgressPollerRegistry]），这个槽就是两者之间的
 * 状态机；把 CAS 语义收进纯 JVM 类，竞态行为可以脱离宿主直接单测。
 *
 * ## 不变式
 *
 * 1. **单槽**：同一时刻至多一个 pending（后 set 覆盖前 set）。两个播放页同时挂起的场景
 *    会丢一个——与旧实现一致，真实出现概率极低（两页同时 bind 且都 core 未就绪）。
 * 2. **消费是抢占式的**：`consumeAny`/`consumeMatching` 用 CAS 拿走，赢家唯一；
 *    输家（校验失败/core 未就绪）用 [restore] 放回，`restore` 只在槽为空时成功。
 * 3. **sinceMs 跟着对象走**：放回不重置时间戳——「挂了多久」必须跨多次放回累计，
 *    否则 stuck 探针永远量不到真实年龄。
 * 4. **[clearIfHost] 按对象身份清**：延迟清理的窗口里新页可能已挂起自己的 pending
 *    （host 是新 widget 实例），无条件清空会把新页的补绑抹掉——此后连 seekDraw 都救不回来。
 */
class PendingBindSlot(private val clock: () -> Long) {

    data class Pending(
        val contextHash: Int,
        val container: Any,
        val host: Any,
        val sinceMs: Long,
    )

    private val ref = AtomicReference<Pending?>(null)

    /** 挂起（覆盖旧值）。时间戳由注入的时钟给出（生产用 uptimeMillis，单调）。 */
    fun set(contextHash: Int, container: Any, host: Any) {
        ref.set(Pending(contextHash, container, host, clock()))
    }

    fun peek(): Pending? = ref.get()

    /** 该 context 是否挂着 pending（轮询 tick 的自停豁免判定用）。 */
    fun peekFor(contextHash: Int): Pending? = ref.get()?.takeIf { it.contextHash == contextHash }

    /** 抢占式拿走任意 pending（不管 context），槽清空。 */
    fun consumeAny(): Pending? = ref.getAndSet(null)

    /** 抢占式拿走**该 context** 的 pending；不属于它则不动槽、返回 null。 */
    fun consumeMatching(contextHash: Int): Pending? {
        val p = ref.get() ?: return null
        if (p.contextHash != contextHash) return null
        return if (ref.compareAndSet(p, null)) p else null
    }

    /** 放回（core 未就绪/校验失败时）：只在槽为空时成功，保留原 [Pending.sinceMs]。 */
    fun restore(pending: Pending): Boolean = ref.compareAndSet(null, pending)

    /**
     * 只清「host 是这个对象」的 pending（=== 身份比较）。
     * 返回是否真的清掉了（诊断/测试用）。
     */
    fun clearIfHost(host: Any): Boolean {
        val p = ref.get() ?: return false
        if (p.host !== host) return false
        return ref.compareAndSet(p, null)
    }
}
