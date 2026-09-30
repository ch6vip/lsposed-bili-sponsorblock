package com.ctf.bilisb.player

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 「进度喂入轮询」的生命周期表 —— 一个纯 JVM 类（只有 JDK 依赖），因此可以被单测直接驱动。
 *
 * ## 为什么要有这张表
 *
 * 6.6.0 的宿主没有任何可依赖的进度 tick（文本控件不实例化、`seek.v3.g#draw` 播放期间不逐帧走、
 * `D0$c.run` 只在 seek 后打一炮），所以**自动跳过完全依赖模块自持的 500ms 轮询**。
 * 这张表一旦失同步，现象是「日志安静地不再跳过」——与「服务端拉不到片段」无法区分。
 *
 * ## 不变式
 *
 * 1. **有 handle ⇒ 有 poller**：起表路径（bind / 补绑 / controller 重建）负责维持，
 *    停表路径（handle 消失 / controller 关闭）负责不破坏。
 * 2. **一个 contextHash 同时只有一个任务**：重复起表是幂等的（不会跑出两条轮询）。
 * 3. **死表项不挡路**：表项已结束/已取消时，再次起表必须能装上新的任务
 *    （旧实现里「表项在、任务已停」会让后续起表请求全部静默失败）。
 * 4. **自停不误杀**：任务自停时只有表里登记的仍是它自己才摘表 —— 否则会把一次新起表装上的
 *    任务取消掉（`start` 与任务自停在竞态窗口里交错时）。
 * 5. 任务体抛异常不外抛：单线程池里外抛会静默杀死整个周期任务。
 *
 * 定时器是 daemon 单线程池（与主线程/宿主无关，进程退出即回收）。
 */
class ProgressPollerRegistry(
    private val intervalMs: Long = 500L,
    /** 任务体抛出的异常统一交到这里（一般打日志）。绝不外抛。 */
    private val onError: (Throwable) -> Unit = {},
) {
    // 注意用 ScheduledExecutorService 接口类型：newSingleThreadScheduledExecutor 返回的是
    // DelegatedScheduledExecutorService 包装类，转成 ScheduledThreadPoolExecutor 会 ClassCastException。
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Bili2233-progress-poll").apply { isDaemon = true }
        }
    private val futures = ConcurrentHashMap<Int, ScheduledFuture<*>>()

    /**
     * 起表（幂等）。[tick] 返回 `true` 表示「该 handle 已不存在」：任务自停。
     *
     * `tick` 只在这条轮询线程上执行，所以它自己不需要额外加锁；表本身由本类的同步块保护。
     */
    @Synchronized
    fun start(contextHash: Int, tick: () -> Boolean) {
        val existing = futures[contextHash]
        if (existing != null && !existing.isDone && !existing.isCancelled) return
        // 死表项（已结束/已取消）必须清掉，否则下面装上的是新任务、表里却留着旧的，
        // 或者反之 —— 两种都会让「有 handle ⇒ 有 poller」这个不变式失效。
        futures.remove(contextHash, existing)
        // 自引用持有者：任务体要能拿「自己」去自停，而 lambda 在 future 生成前就构造好了。
        val self = arrayOfNulls<ScheduledFuture<*>>(1)
        val future = executor.scheduleWithFixedDelay(
            {
                try {
                    if (tick()) stop(contextHash, thisFuture = self[0])
                } catch (t: Throwable) {
                    onError(t)
                }
            },
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS,
        )
        self[0] = future
        futures[contextHash] = future
    }

    /**
     * 停表（幂等）。
     *
     * [thisFuture] 为任务体自停时传进来的「自己」：只有表里登记的仍是它才摘表并取消，
     * 否则说明表项已被一次新的起表换掉，不能误杀新任务。
     */
    @Synchronized
    fun stop(contextHash: Int, thisFuture: ScheduledFuture<*>? = null) {
        val registered = futures[contextHash] ?: return
        if (thisFuture != null && registered !== thisFuture) return
        futures.remove(contextHash, registered)
        // 不用 ?. 短路：remove 返回 null 时 cancel 根本不会执行（表项已被别处摘掉、任务还在跑）。
        registered.cancel(false)
    }

    /** 停掉全部（模块关闭 / SponsorBlock 总开关关闭）。 */
    @Synchronized
    fun stopAll() {
        futures.keys.toList().forEach { stop(it) }
    }

    /** 该 context 当前是否有活着的轮询任务（诊断与测试用）。 */
    fun isRunning(contextHash: Int): Boolean {
        val future = futures[contextHash] ?: return false
        return !future.isDone && !future.isCancelled
    }

    /** 当前登记的任务数（诊断与测试用）。 */
    fun size(): Int = futures.size
}
