package com.ctf.bilisb.unlock

import android.content.res.Configuration
import android.content.res.Resources
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 视频清晰度策略（对齐 BiliRoaming full_screen_quality / half_screen_quality 与 C0043bk / D0）。
 *
 * 支持半屏/全屏默认清晰度锁定与「自动最高」清晰度策略。
 * 当策略激活时，自动将 PlayView 请求的 fnval 升档至 4048 并启用 fourk(4K)，破除高画质限制。
 */
object QualityPolicy {

    const val QUALITY_DEFAULT = 0
    const val QUALITY_FOLLOW_FULL = 1
    const val QUALITY_AUTO_HIGHEST = -1

    const val QN_240P = 6
    const val QN_360P = 16
    const val QN_480P = 32
    const val QN_720P = 64
    const val QN_720P_60 = 74
    const val QN_1080P = 80
    const val QN_1080P_PLUS = 112
    const val QN_1080P_60 = 116
    const val QN_4K = 120
    const val QN_8K = 127

    val FULL_SCREEN_OPTIONS = listOf(
        "0" to "默认",
        "-1" to "自动最高",
        "127" to "8K 超高清",
        "120" to "4K 超清",
        "116" to "1080P 60帧",
        "112" to "1080P 高码率",
        "80" to "1080P 高清",
        "74" to "720P 60帧",
        "64" to "720P 高清",
        "32" to "480P 清晰",
        "16" to "360P 流畅",
        "6" to "240P 极速",
    )

    val HALF_SCREEN_OPTIONS = listOf(
        "0" to "默认",
        "1" to "跟随全屏清晰度",
        "-1" to "自动最高",
        "127" to "8K 超高清",
        "120" to "4K 超清",
        "116" to "1080P 60帧",
        "112" to "1080P 高码率",
        "80" to "1080P 高清",
        "74" to "720P 60帧",
        "64" to "720P 高清",
        "32" to "480P 清晰",
        "16" to "360P 流畅",
        "6" to "240P 极速",
    )

    private val installed = AtomicBoolean(false)

    /**
     * 判断当前是否处于全屏/横屏模式。
     */
    fun isLandscape(): Boolean {
        return runCatching {
            val orientation = Resources.getSystem().configuration.orientation
            orientation == Configuration.ORIENTATION_LANDSCAPE
        }.getOrDefault(false)
    }

    /**
     * 根据设置与屏幕朝向解析期望的 QN。
     * 返回 0 代表使用默认（不干涉），-1 代表自动最高画质，>0 代表目标清晰度代码。
     */
    fun resolveTargetQn(
        fullScreenPref: String,
        halfScreenPref: String,
        isLandscape: Boolean = isLandscape(),
    ): Int {
        val fsVal = fullScreenPref.trim().toIntOrNull() ?: QUALITY_DEFAULT
        val hsVal = halfScreenPref.trim().toIntOrNull() ?: QUALITY_DEFAULT

        return if (isLandscape) {
            fsVal
        } else {
            if (hsVal == QUALITY_FOLLOW_FULL) {
                fsVal
            } else {
                hsVal
            }
        }
    }

    /**
     * 是否需要修补请求（请求高规格 Dash 流或指定清晰度）。
     */
    fun shouldPatchQuality(fullScreenPref: String, halfScreenPref: String): Boolean {
        val fs = fullScreenPref.trim()
        val hs = halfScreenPref.trim()
        return (fs != "0" && fs.isNotEmpty()) || (hs != "0" && hs.isNotEmpty())
    }

    /**
     * 对 PlayViewReq / PlayViewUniteReq 或其 Builder 进行画质参数注入。
     */
    fun patchReqObject(reqOrBuilder: Any, targetQn: Int) {
        val cls = reqOrBuilder.javaClass
        runCatching {
            // setFnval(4048) 支持所有高级清晰度与编码
            cls.methods.firstOrNull { it.name == "setFnval" && it.parameterTypes.size == 1 }
                ?.invoke(reqOrBuilder, 4048)
        }
        runCatching {
            // setFourk(true)
            cls.methods.firstOrNull { it.name == "setFourk" && it.parameterTypes.size == 1 }
                ?.let { m ->
                    val paramType = m.parameterTypes[0]
                    if (paramType == Boolean::class.javaPrimitiveType || paramType == java.lang.Boolean::class.java) {
                        m.invoke(reqOrBuilder, true)
                    } else if (paramType == Int::class.javaPrimitiveType || paramType == java.lang.Integer::class.java) {
                        m.invoke(reqOrBuilder, 1)
                    }
                }
        }
        if (targetQn > 0) {
            runCatching {
                cls.methods.firstOrNull { it.name == "setQn" && it.parameterTypes.size == 1 }
                    ?.let { m ->
                        val paramType = m.parameterTypes[0]
                        if (paramType == Long::class.javaPrimitiveType || paramType == java.lang.Long::class.java) {
                            m.invoke(reqOrBuilder, targetQn.toLong())
                        } else {
                            m.invoke(reqOrBuilder, targetQn)
                        }
                    }
            }
        } else if (targetQn == QUALITY_AUTO_HIGHEST) {
            runCatching {
                // 自动最高：请求 8K/4K 规格，播放器收到后自动协商最高可用流
                cls.methods.firstOrNull { it.name == "setQn" && it.parameterTypes.size == 1 }
                    ?.let { m ->
                        val paramType = m.parameterTypes[0]
                        if (paramType == Long::class.javaPrimitiveType || paramType == java.lang.Long::class.java) {
                            m.invoke(reqOrBuilder, 127L)
                        } else {
                            m.invoke(reqOrBuilder, 127)
                        }
                    }
            }
        }
    }

    /**
     * 宿主清晰度策略拦截钩子（对齐 BiliRoaming C0043bk）。
     */
    fun install(module: XposedModule, cl: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        // 尝试钩住 Bilibili 播放器策略候选类
        val candidates = listOf(
            "com.bilibili.videopage.data.view.QualityStrategyProvider",
            "tv.danmaku.biliplayerv2.quality.QualityStrategyProvider",
        )
        for (name in candidates) {
            runCatching {
                val clazz = cl.loadClass(name)
                for (m in clazz.declaredMethods) {
                    if (m.parameterTypes.size == 6 && m.parameterTypes.all { it == Int::class.javaPrimitiveType }) {
                        runCatching { m.isAccessible = true }
                        runCatching { module.deoptimize(m) }
                        module.hook(m)
                            .setPriority(XposedInterface.PRIORITY_DEFAULT)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                val config = UnlockConfig.load(module)
                                val hs = config.halfScreenQuality.toIntOrNull() ?: 0
                                val fs = config.fullScreenQuality.toIntOrNull() ?: 0
                                if (hs == 0 && fs == 0) return@intercept chain.proceed()
                                val newArgs = chain.args.toTypedArray()
                                if (hs != 0) {
                                    val q = if (hs == QUALITY_AUTO_HIGHEST) 127 else hs
                                    newArgs[0] = q
                                    newArgs[3] = q
                                    newArgs[4] = q
                                    newArgs[5] = q
                                }
                                if (fs != 0) {
                                    val q = if (fs == QUALITY_AUTO_HIGHEST) 127 else fs
                                    newArgs[1] = q
                                    newArgs[2] = q
                                }
                                chain.proceed(newArgs)
                            }
                        HookProbe.ok(module, "unlock:qualityStrategy", "hooked $name#${m.name}")
                    }
                }
            }
        }
    }
}
