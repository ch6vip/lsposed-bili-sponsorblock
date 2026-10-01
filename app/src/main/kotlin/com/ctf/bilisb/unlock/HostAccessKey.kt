package com.ctf.bilisb.unlock

import java.util.concurrent.atomic.AtomicReference

/**
 * 宿主运行时 access_key 的被动捕获（解锁 U4.7）。
 *
 * 参考实现的做法：反射调用宿主账户管理器（BiliAccounts.getAccessKey）拿令牌。
 * 我们的等价路径更被动：宿主 REST 公共参数拦截器（addCommonParam）的 Map 里
 * **必然携带 access_key**（App 的所有 REST 请求都经它），而该拦截器已被
 * IpLocationHooks 挂钩（6.6.0 全会话 hook ok）——在既有回调里顺手捕获即可，
 * 不新增任何 hook 点。
 *
 * 隐私：令牌只留在宿主进程内存（静态引用），不落盘、不打日志全文（探针只记长度）。
 */
object HostAccessKey {

    private val lastSeen = AtomicReference<String?>(null)

    /** gripper getAccessKey 钩子调用：直接捕获令牌。 */
    fun capture(token: String) {
        if (token.isEmpty()) return
        lastSeen.set(token)
    }

    /** addCommonParam 回调里调用：Map 含 access_key 时更新（幂等，开销一次 map 查找）。 */
    fun capture(map: Map<*, *>) {
        (map["access_key"] as? String)?.takeIf { it.isNotEmpty() }?.let { capture(it) }
    }

    /** 最近捕获的令牌（可能为 null：宿主还没发过 REST 请求）。 */
    fun lastSeen(): String? = lastSeen.get()
}
