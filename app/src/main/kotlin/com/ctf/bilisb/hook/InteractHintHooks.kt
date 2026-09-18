package com.ctf.bilisb.hook

import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HookResolve
import com.ctf.bilisb.settings.EnhanceFlags
import com.ctf.bilisb.util.info
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier

/**
 * 隐藏视频内互动提示（移植自 BiliTamer (MIT) 的 InteractHintHooks.java）：
 *  - 一键三连：点赞/投币/收藏连击动画与文案；
 *  - UP 提示：关注引导气泡；
 *  - 投票：互动弹幕投票面板。
 *
 * BiliTamer 逆向结论（6.3.0 落点，6.5.0 沿用同名类/方法，标 ** 的为 6.5.0 目标）：
 *  - 一键三连：com.bilibili.app.gemini.player.widget.like.VideoTripleLike
 *      - `setPrompt(boolean)`：三连提示文案开关，开关打开时强制传 false；
 *      - `getToast()`：提示文案 getter，开关打开时返回空串。
 *  - UP 提示：com.bilibili.playerbizcommonv2.widget.popup.FollowPopupUtil
 *      `b(...)`（静态、2 参、void）是关注引导气泡入口，直接拦截不执行。
 *      不匹配具体参数类型 —— 混淆签名会变，按「名字 + 参数个数 + 静态 void」结构匹配。
 *  - 投票/互动弹幕：
 *      com.bilibili.playerbizcommonv2.danmaku.command.InteractDanmakuListWidget
 *      `setData(List)` 数据入口，置空列表即不显示。
 *
 * 每个子 hook 独立 try 安装（一个失败不能拖垮其它），装好/失败都走 [HookProbe]。
 * 开关在每次回调里实时读 [EnhanceFlags.snapshot]（不缓存布尔值），保证热生效。
 */
object InteractHintHooks {

    fun install(module: XposedModule, cl: ClassLoader) {
        installGroup(module, "hintTriple") { installTriplePrompt(module, cl) }
        installGroup(module, "hintFollowPopup") { installFollowPopup(module, cl) }
        installGroup(module, "hintVote") { installVote(module, cl) }
        module.info("InteractHintHooks installed")
    }

    /** 单组独立兜底：某组安装失败只记 MISS，不影响其它组。 */
    private inline fun installGroup(module: XposedModule, key: String, block: () -> Unit) {
        runCatching { block() }.onFailure { t ->
            HookProbe.miss(module, key, "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------ 一键三连

    private fun installTriplePrompt(module: XposedModule, cl: ClassLoader) {
        val vtl = Class.forName(
            "com.bilibili.app.gemini.player.widget.like.VideoTripleLike",
            false,
            cl,
        )
        var hooked = 0

        // setPrompt(boolean)：三连提示文案开关
        HookResolve.declaredMethod(vtl, listOf("setPrompt"), java.lang.Boolean.TYPE)?.let { method ->
            runCatching { module.deoptimize(method) }
            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // 决策与 proceed 分离：runCatching 里只做判定，proceed 只会发生一次，
                    // 我们的异常最多退化成「不干预」，绝不在 getOrElse 里二次 proceed。
                    val hide = runCatching {
                        // thisObject 是 widget(View)：顺手捕获 Context 供 EnhanceFlags 读设置
                        val host = chain.getThisObject()
                        if (host != null) EnhanceFlags.captureContext(host)
                        EnhanceFlags.snapshot(module).hideTriple
                    }.getOrElse { false }
                    if (hide) chain.proceed(arrayOf<Any?>(false)) else chain.proceed()
                }
            HookProbe.ok(module, "hintTriple:setPrompt", "VideoTripleLike#setPrompt(boolean)")
            hooked++
        } ?: HookProbe.miss(module, "hintTriple:setPrompt", "VideoTripleLike#setPrompt not found")

        // getToast()：清空提示文案
        HookResolve.declaredMethod(vtl, listOf("getToast"))?.let { method ->
            runCatching { module.deoptimize(method) }
            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val hide = runCatching {
                        val host = chain.getThisObject()
                        if (host != null) EnhanceFlags.captureContext(host)
                        EnhanceFlags.snapshot(module).hideTriple
                    }.getOrElse { false }
                    if (hide) "" else result
                }
            HookProbe.ok(module, "hintTriple:getToast", "VideoTripleLike#getToast")
            hooked++
        } ?: HookProbe.miss(module, "hintTriple:getToast", "VideoTripleLike#getToast not found")

        if (hooked > 0) {
            module.info("hint: triple prompt hook ok -> VideoTripleLike")
        }
    }

    // ------------------------------------------------------------ UP 提示

    /** UP 提示：FollowPopupUtil.b(...) 关注引导气泡入口直接跳过。 */
    private fun installFollowPopup(module: XposedModule, cl: ClassLoader) {
        val fpu = Class.forName(
            "com.bilibili.playerbizcommonv2.widget.popup.FollowPopupUtil",
            false,
            cl,
        )
        val target = fpu.declaredMethods.firstOrNull { m ->
            m.name == "b" &&
                m.parameterTypes.size == 2 &&
                m.returnType == Void.TYPE &&
                Modifier.isStatic(m.modifiers)
        } ?: run {
            HookProbe.miss(module, "hintFollowPopup", "FollowPopupUtil.b signature not found; skipped")
            return
        }
        runCatching { target.isAccessible = true }
        runCatching { module.deoptimize(target) }
        module.hook(target)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val hide = runCatching {
                    // 静态方法没有 thisObject，参数里可能带 Context/View，顺手捕获
                    chain.getArgs().forEach { arg -> if (arg != null) EnhanceFlags.captureContext(arg) }
                    EnhanceFlags.snapshot(module).hideUpPrompt
                }.getOrElse { false }
                if (hide) null else chain.proceed() // 拦截，不执行弹窗
            }
        HookProbe.ok(module, "hintFollowPopup", "FollowPopupUtil#b(${target.parameterTypes.size} params, static)")
        module.info("hint: follow popup hook ok -> FollowPopupUtil.b")
    }

    // ------------------------------------------------------------ 投票/互动弹幕

    /** 投票/互动弹幕：InteractDanmakuListWidget.setData(List) 置空。 */
    private fun installVote(module: XposedModule, cl: ClassLoader) {
        val widget = Class.forName(
            "com.bilibili.playerbizcommonv2.danmaku.command.InteractDanmakuListWidget",
            false,
            cl,
        )
        val method = HookResolve.declaredMethod(widget, listOf("setData"), java.util.List::class.java)
            ?: run {
                HookProbe.miss(module, "hintVote", "InteractDanmakuListWidget#setData(List) not found")
                return
            }
        runCatching { module.deoptimize(method) }
        module.hook(method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val hide = runCatching {
                    val host = chain.getThisObject()
                    if (host != null) EnhanceFlags.captureContext(host)
                    EnhanceFlags.snapshot(module).hideVote
                }.getOrElse { false }
                if (hide) chain.proceed(arrayOf<Any?>(null)) else chain.proceed()
            }
        HookProbe.ok(module, "hintVote", "InteractDanmakuListWidget#setData(List)")
        module.info("hint: vote hook ok -> InteractDanmakuListWidget.setData")
    }
}
