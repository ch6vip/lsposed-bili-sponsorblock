package com.ctf.bilisb.host

import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Hook 点的通用解析工具。
 *
 * 宿主混淆名会随版本变化，所以这里一律「按候选名字 + 签名形状」解析，
 * 取第一个存在的目标；解析失败不抛异常，只交给 [HookProbe] 记录。
 */
object HookResolve {
    /** 按候选顺序返回第一个能加载的类。 */
    fun findClass(classLoader: ClassLoader, candidates: List<String>): Class<*>? {
        for (name in candidates) {
            val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
            if (clazz != null) return clazz
        }
        return null
    }

    /** 只在指定类上找声明的方法（不查父类）。 */
    fun declaredMethod(clazz: Class<*>, methodNames: List<String>, vararg paramTypes: Class<*>): Method? {
        for (name in methodNames) {
            val method = runCatching { clazz.getDeclaredMethod(name, *paramTypes) }.getOrNull()
            if (method != null) {
                runCatching { method.isAccessible = true }
                return method
            }
        }
        return null
    }

    /**
     * 在对象上按候选方法名 + 参数类型找方法：先本类，再父类，最后接口。
     *
     * 接口上的抽象方法同样可以反射调用（会走虚分派），6.5.0 的
     * `service.D#o(int,boolean)` 这类抽象方法就是靠这条路径拿到的。
     */
    fun forTarget(target: Any, methodNames: List<String>, vararg paramTypes: Class<*>): Method? {
        val visited = LinkedHashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue.add(target.javaClass)
        while (queue.isNotEmpty()) {
            val clazz = queue.removeFirst()
            if (!visited.add(clazz)) continue
            declaredMethod(clazz, methodNames, *paramTypes)?.let { return it }
            clazz.interfaces.forEach { queue.add(it) }
            clazz.superclass?.let { queue.add(it) }
        }
        return null
    }

    /**
     * 按「名字候选 + 参数个数 + 参数类型名判定」找方法。
     *
     * 用于参数类型本身也是混淆名（例如容器接口 `tv.danmaku.biliplayerv2.f`）的情况：
     * 只比较类型名字符串，不要求该类型已加载。
     */
    fun declaredMethodByShape(
        clazz: Class<*>,
        methodNames: List<String>,
        arity: Int,
        paramTypeName: ((String) -> Boolean)? = null,
    ): Method? {
        for (name in methodNames) {
            val found = clazz.declaredMethods.firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.size == arity &&
                    (paramTypeName == null || method.parameterTypes.all { paramTypeName(it.name) })
            }
            if (found != null) {
                runCatching { found.isAccessible = true }
                return found
            }
        }
        return null
    }

    /** 在对象上按候选方法名调用无参方法，取第一个返回非 null 的结果。 */
    fun invokeNoArg(target: Any, methodNames: List<String>): Any? {
        for (name in methodNames) {
            val method = forTarget(target, listOf(name)) ?: continue
            val value = runCatching { method.invoke(target) }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    /** 读取对象字段（含私有），失败返回 null。 */
    fun readField(target: Any, fieldName: String): Any? {
        return runCatching {
            val field = target.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
            field.get(target)
        }.getOrNull()
    }
}

/**
 * Hook 探针：记录每条 Hook 是否命中，并提供限频日志。
 *
 * 目的：宿主改版后 Hook 失效是**静默**的，只靠业务日志很难判断。
 * 探针把「装了哪些 hook / 哪些没找到」汇总成一张表打进日志；
 * 迁移期还可以用限频日志打印运行时候选信息（真正回调的是哪个方法、drawable 实际是什么类）。
 */
object HookProbe {
    private val results = LinkedHashMap<String, String>()
    private val firstCounters = ConcurrentHashMap<String, Int>()

    @Synchronized
    fun ok(module: XposedModule, key: String, target: String) {
        results[key] = "OK   $target"
        module.info("[probe] hook ok: $key <- $target")
    }

    @Synchronized
    fun miss(module: XposedModule, key: String, detail: String = "") {
        results[key] = "MISS $detail"
        module.info("[probe] hook miss: $key${if (detail.isEmpty()) "" else " ($detail)"}")
    }

    @Synchronized
    fun skip(module: XposedModule, key: String, reason: String) {
        results[key] = "SKIP $reason"
        module.info("[probe] hook skip: $key ($reason)")
    }

    /** 汇总当前 Hook 命中情况（安装完 / 进播放页前各打一次）。 */
    @Synchronized
    fun summary(): String {
        if (results.isEmpty()) return "[probe] no hooks installed"
        val okCount = results.values.count { it.startsWith("OK") }
        return buildString {
            append("[probe] hook summary: ").append(okCount).append("/").append(results.size).append(" hit")
            results.forEach { (key, value) -> append("\n  ").append(key).append(" : ").append(value) }
        }
    }

    /**
     * 只记录前 [times] 次（用于探针期打印回调实参，避免刷屏）。
     *
     * 注意：探针绝不允许把异常抛给宿主 —— 调用方给的 [message] 可能读到 null 实参，
     * 这里统一兜住（曾经因为探针里一个非空 lambda 参数把宿主进程崩掉过，见 docs/STATUS.md）。
     */
    fun first(module: XposedModule, key: String, times: Int, message: () -> String) {
        val count = firstCounters.merge(key, 1, Int::plus) ?: 1
        if (count == times + 1) {
            // 记满后从 map 移除键:probe 键(如 seekDraw:$className:$instanceId)按实例生成,
            // 长会话会缓慢累积;计数已达上限时最后一次 merge 的值就是 times+1,移除防止无界增长
            firstCounters.remove(key, count)
        }
        if (count <= times) {
            module.info("[probe] $key #$count: ${safeMessage(message)}")
        }
    }

    private fun safeMessage(message: () -> String): String {
        return runCatching { message() }.getOrElse { "probe message failed: ${it.javaClass.simpleName}: ${it.message}" }
    }
}
