package com.ctf.bilisb.player

import java.util.Collections
import java.util.WeakHashMap

/**
 * 「向宿主注册观察者」的去重闸门 —— 一个纯 JVM 类，**没有任何宿主/框架依赖**，
 * 就是为了能被单测直接跑（`ObserverRegistrationGateTest`）。
 *
 * ## 为什么单独抽出来
 *
 * 2026-09-29 的详情页黑屏 + 输入 ANR 根因在这里：我们 hook 的是宿主自己的
 * `addVideoDirectorObserver`（真机上是 `PlayDirectorServiceV3#l0`），而注册时用的是
 * **反射调用**同一方法 —— LSPosed 对反射调用同样生效，所以 after 回调会**重入**
 * `VideoDirectorListener.registerDirectorService`。因此「登记」必须发生在 invoke **之前**，
 * 重入才会在 `isRegistered` 处终止。
 *
 * 这段顺序要求曾被一次代码审查无意破坏（去重点从 invoke 前挪到 invoke 后），
 * 直接后果是 6.6.0 在 `onCreate` 主线程内同步注册时无限重入 → 黑屏 + ANR。
 * 顺序是**不可见的契约**，所以把它连同一个断言该顺序的测试一起固化在这里。
 *
 * ## 契约（`ObserverRegistrationGateTest` 逐条覆盖）
 *
 * 1. 登记与 `tryRegister` 的调用顺序：登记在先 —— 否则 `onRegister` 内部重入 `register`
 *    会无限递归。
 * 2. 已登记的服务直接返回 `true`，不重复注册。
 * 3. `onRegister` 抛异常时登记回滚，下次还能重试（不能留下「已登记但其实没注册」的死账，
 *    注销路径依赖这张表）。
 * 4. 弱引用：宿主服务实例被回收后不应被这张表长期持有。
 *
 * 线程安全：与 `VideoDirectorListener` 原实现一致，用 `synchronizedMap(WeakHashMap)` 包住，
 * **不引入新的同步原语**（注册发生在宿主 `onStart`/`onCreate`，注销在 `onStop`，单线程为主）。
 */
class ObserverRegistrationGate<V> {
    /** 已成功登记（= 已发起注册）的服务实例 → 关联值。 */
    private val registered: MutableMap<Any, V> =
        Collections.synchronizedMap(WeakHashMap<Any, V>())

    /** 该服务是否已登记。 */
    fun isRegistered(service: Any): Boolean = registered.containsKey(service)

    /** 读回登记时存下的关联值（例如观察者代理与接口的配对）。 */
    fun get(service: Any): V? = registered[service]

    /**
     * 登记 [service] 并执行 [onRegister]（真正的注册动作）。
     *
     * **先登记再调用 [onRegister]**：`onRegister` 内部可能（经由被 hook 的宿主方法）
     * 重入本方法，登记在先才能让重入在 `isRegistered` 处终止。
     *
     * @return `onRegister` 是否成功；失败或重入时返回 `false`（重入返回 `false` 是刻意的：
     *   重入的这一层并没有真的发起注册，成功了才是错的）。
     */
    fun register(service: Any, value: V, onRegister: () -> Unit): Boolean {
        if (registered.containsKey(service)) return false
        registered[service] = value
        return try {
            onRegister()
            true
        } catch (t: Throwable) {
            // 回滚：注册失败必须摘掉登记，否则注销路径会拿到一个从未注册过的观察者
            // （对宿主调 removeObserver 无副作用，但会让我们永远不再重试注册）。
            registered.remove(service, value)
            throw t
        }
    }

    /** 注销成功后才摘表；失败保留，交给下次重试。 */
    fun unregister(service: Any, value: V, onUnregister: () -> Unit) {
        if (registered[service] !== value) return
        onUnregister()
        registered.remove(service, value)
    }

    /** 仅供测试与诊断：当前登记数量。 */
    fun size(): Int = registered.size
}
