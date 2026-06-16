package com.ctf.bilisb.player

import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field

/**
 * 8.96.0 原版 video id 探针。
 *
 * 8.98.0 patch 版的 `VideoDirectorObserver.onStart + getLogDescription` 链路在 8.96.0 原版不成立:
 *  - 原版 `VideoDirectorObserver` 接口方法是 `onItemStart`/`onItemWillChange`,director service 用
 *    `addVideoPlayEventListener` 而非 `addVideoDirectorObserver`。
 *  - 原版 `Video` 对象不存 aid/cid(`getId()` 返回对象 hash,`getDescription()` 返回 "video")。
 *  - `getLogDescription()` 在 `*PlayableParams` 类上,不在 director/Video 上。
 *
 * 因此 8.96.0 的 aid/cid 来源需要在运行时确认。本探针在容器创建后取 `PlayerParamsV2`,
 * 递归 dump 其字段树,把所有看起来像 aid(avid)/cid/bvid 的字段打印到日志,装机后即可定位
 * 真实字段路径,再替换为确定的读取逻辑。
 *
 * 当前为探针实现,aid/cid 的精确字段路径标注为待运行时确认。
 */
object VideoIdProbe {
    private const val GET_PLAYER_PARAMS = "getPlayerParams"

    /**
     * 在 [playerContainer] 上探查 video id。
     * 容器创建后调 `getPlayerParams()` 拿 PlayerParamsV2,递归 dump 字段。
     * 命中候选字段时回调 [onVideoIds]。
     */
    fun probe(module: XposedModule, playerContainer: Any, onVideoIds: (aid: Long, cid: Long) -> Unit) {
        val params = invokeNoArg(playerContainer, GET_PLAYER_PARAMS) ?: run {
            module.info("videoIdProbe: getPlayerParams null on ${playerContainer.javaClass.name}")
            return
        }
        module.info("videoIdProbe: params class=${params.javaClass.name}")
        val found = scanForIds(module, params, depth = 0, seen = mutableSetOf())
        if (found != null) {
            module.info("videoIdProbe: FOUND aid=${found.first} cid=${found.second}")
            onVideoIds(found.first, found.second)
        } else {
            module.info("videoIdProbe: no aid/cid candidate in PlayerParamsV2 tree (see field dump above)")
        }
    }

    private fun scanForIds(
        module: XposedModule,
        target: Any,
        depth: Int,
        seen: MutableSet<Int>,
    ): Pair<Long, Long>? {
        if (depth > 3 || !seen.add(System.identityHashCode(target))) {
            return null
        }

        var aid: Long = 0L
        var cid: Long = 0L
        val clazz = target.javaClass

        for (field in clazz.declaredFields) {
            val value = runCatching {
                field.isAccessible = true
                field.get(target)
            }.getOrNull() ?: continue

            // 按字段名匹配候选;8.96.0 字段名待运行时确认,这里覆盖常见命名。
            val name = field.name
            when {
                name.equals("cid", ignoreCase = true) && value is Number -> cid = value.toLong()
                name.equals("cid", ignoreCase = true) && value is String -> value.toLongOrNull()?.let { cid = it }
                name.equals("aid", ignoreCase = true) || name.equals("avid", ignoreCase = true) || name.equals("avId", ignoreCase = true) -> {
                    when (value) {
                        is Number -> aid = value.toLong()
                        is String -> value.toLongOrNull()?.let { aid = it }
                    }
                }
            }

            // 仅对 B 站自有类型(非 java/android/kotlin 标准类)递归,避免爆炸。
            val valueClass = value.javaClass
            val cn = valueClass.name
            if (depth < 2 &&
                !cn.startsWith("java.") &&
                !cn.startsWith("android.") &&
                !cn.startsWith("kotlin.") &&
                !cn.startsWith("org.jetbrains.")
            ) {
                module.info("videoIdProbe: ${indent(depth)}$name:${cn}=$value")
                val nested = scanForIds(module, value, depth + 1, seen)
                if (nested != null && (aid == 0L || cid == 0L)) {
                    if (aid == 0L) aid = nested.first
                    if (cid == 0L) cid = nested.second
                }
                if (nested != null && aid > 0 && cid > 0) return aid to cid
            }
        }

        return if (aid > 0 && cid > 0) aid to cid else null
    }

    private fun indent(depth: Int): String = "  ".repeat(depth)

    private fun invokeNoArg(target: Any, methodName: String): Any? {
        return runCatching {
            val method = target.javaClass.getDeclaredMethod(methodName).apply { isAccessible = true }
            method.invoke(target)
        }.getOrNull()
    }
}
