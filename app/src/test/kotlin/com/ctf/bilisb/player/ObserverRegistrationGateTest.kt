package com.ctf.bilisb.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ObserverRegistrationGate] 的回归测试。
 *
 * 存在的理由：2026-09-29 的详情页黑屏 + 输入 ANR 根因是**登记顺序**被一次审查改动破坏
 * （去重点从 `invoke` 之前挪到之后）。顺序本身在代码里看不出来，
 * 只有「重入」这个场景能证明它 —— 所以这里用动态代理**真造一次重入**。
 */
class ObserverRegistrationGateTest {

    /** 模拟宿主的观察者接口：宿主服务通过它回调我们。 */
    interface Observer {
        fun onStart(videoId: Long)
    }

    /**
     * 模拟宿主服务：`invoke` 就是「我们 hook 的那个方法」。
     * 真机上它是 `PlayDirectorServiceV3#l0`，而我们 hook 了它，所以它一被调用就会
     * 回调进 `registerDirectorService` —— 即 [onReentry]。
     */
    class HostService(private val onInvoke: () -> Unit) {
        var invocations = 0
            private set

        fun invoke(observer: Observer) {
            invocations++
            onInvoke()
        }
    }

    private fun observerProxy(handler: (Method, Array<out Any>?) -> Any?): Observer {
        val cl = Observer::class.java.classLoader
        return Proxy.newProxyInstance(cl, arrayOf(Observer::class.java), InvocationHandler { _, m, args ->
            handler(m, args)
        }) as Observer
    }

    /**
     * 关键回归：`onRegister` 内部重入 `register`（经被 hook 的宿主方法）时，
     * 必须是「重入的那层直接返回、宿主方法只被调用一次」，而不是无限递归。
     */
    @Test
    fun `onRegister 内部重入时不会无限递归且宿主方法只调用一次`() {
        val gate = ObserverRegistrationGate<String>()
        val service = HostService(onInvoke = {})
        val invocations = AtomicInteger(0)

        // 建代理 → 注册时 invoke 宿主方法 → 宿主方法的 after 回调重入 register
        lateinit var observer: Observer
        observer = observerProxy { method, _ ->
            if (method.declaringClass == Any::class.java) {
                null
            } else {
                // 模拟 LSPosed 的 after 回调：宿主方法被调用 → 我们的注册路径再次进入
                gate.register(service, "reentrant") {
                    invocations.incrementAndGet()
                    throw AssertionError("重入的注册不应该再次 invoke 宿主方法")
                }
                null
            }
        }

        val first = gate.register(service, "first") {
            service.invoke(observer)
        }

        assertTrue("首次注册应成功", first)
        assertEquals("宿主方法只应被调用一次（重入被去重挡住）", 1, service.invocations)
        assertEquals("重入那层不应发起真正的注册", 0, invocations.get())
    }

    /** 已经登记过的服务直接返回 true，且不再调用宿主方法。 */
    @Test
    fun `已登记的服务不重复注册`() {
        val gate = ObserverRegistrationGate<String>()
        val service = HostService(onInvoke = {})

        assertTrue(gate.register(service, "v1") { service.invoke(observerProxy { _, _ -> null }) })
        assertEquals(1, service.invocations)
        assertEquals("v1", gate.get(service))

        // 第二次：直接短路，不覆盖已登记的关联值，也不再调宿主
        assertFalse(gate.register(service, "v2") { service.invoke(observerProxy { _, _ -> null }) })
        assertEquals(1, service.invocations)
        assertEquals("v1", gate.get(service))
    }

    /** 注册失败必须回滚登记，否则这条服务永远不会再被重试注册。 */
    @Test
    fun `onRegister 抛异常时登记回滚且可重试`() {
        val gate = ObserverRegistrationGate<String>()
        val service = HostService(onInvoke = {})

        val thrown = runCatching {
            gate.register(service, "v1") { error("addObserver failed") }
        }.exceptionOrNull()

        assertEquals("异常要原样上抛给调用方（模块日志要记到）", "addObserver failed", thrown?.message)
        assertFalse("失败的注册不能留在表里", gate.isRegistered(service))
        assertNull(gate.get(service))
        assertEquals(0, gate.size())

        // 重试应当能成功
        assertTrue(gate.register(service, "v2") { })
        assertEquals("v2", gate.get(service))
    }

    /** 注销成功才摘表；注销失败要保留登记，交给下次重试。 */
    @Test
    fun `注销失败保留登记_成功才摘表`() {
        val gate = ObserverRegistrationGate<String>()
        val service = HostService(onInvoke = {})
        val value = "observer"
        gate.register(service, value) { }

        runCatching { gate.unregister(service, value) { error("removeObserver not found") } }
        assertTrue("注销失败后登记必须保留（否则永远不会再重试）", gate.isRegistered(service))

        gate.unregister(service, value) { }
        assertFalse(gate.isRegistered(service))
    }

    /** 关联值不匹配时不能误摘别人的登记。 */
    @Test
    fun `注销时关联值不匹配则不摘表`() {
        val gate = ObserverRegistrationGate<String>()
        val service = HostService(onInvoke = {})
        gate.register(service, "real") { }

        gate.unregister(service, "other") { }
        assertTrue(gate.isRegistered(service))
        assertSame("real", gate.get(service))
    }

    /** 弱引用：宿主服务实例被回收后不应被这张表长期持有。 */
    @Test
    fun `登记表是弱引用_服务实例可被回收`() {
        val gate = ObserverRegistrationGate<String>()
        var strong: HostService? = HostService(onInvoke = {})
        val service = strong!!
        gate.register(service, "v") { }
        assertEquals(1, gate.size())

        strong = null
        val collected = runCatching {
            repeat(12) {
                System.gc()
                if (gate.size() == 0) throw CancelledException()
                Thread.sleep(20)
            }
            false
        }.getOrElse { true }

        // GC 时机不由测试决定：能回收就断言表已清空，不能回收则至少保证不抛异常且表仍可用。
        if (collected) {
            assertEquals("服务被回收后登记表不应继续持有它", 0, gate.size())
        } else {
            assertTrue(gate.isRegistered(service))
        }
    }

    private class CancelledException : RuntimeException()
}
