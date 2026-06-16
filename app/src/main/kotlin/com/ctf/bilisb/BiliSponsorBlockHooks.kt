package com.ctf.bilisb

import android.graphics.Canvas
import android.os.Bundle
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.ctf.bilisb.player.PlayerBridge
import com.ctf.bilisb.player.PlayerHandle
import com.ctf.bilisb.player.VideoDirectorListener
import com.ctf.bilisb.sponsor.SponsorBlockController
import com.ctf.bilisb.ui.ProgressTextDecorator
import com.ctf.bilisb.ui.ProgressMarkerPainter
import com.ctf.bilisb.ui.RemainingTimeFormatter
import com.ctf.bilisb.ui.SubmissionButtonInjector
import com.ctf.bilisb.util.info
import com.ctf.bilisb.util.ProbeLogger
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object BiliSponsorBlockHooks {
    private val installed = ConcurrentHashMap.newKeySet<String>()
    private var sponsorBlockController: SponsorBlockController? = null

    fun install(module: XposedModule, param: PackageLoadedParam, processName: String) {
        val installKey = "${param.packageName}:$processName"
        if (!installed.add(installKey)) {
            return
        }

        val cl = param.defaultClassLoader
        module.info("Installing hooks for ${param.packageName} process=$processName with $cl")
        sponsorBlockController = SponsorBlockController(module)

        hookPlayerContainer(module, cl)
        hookProgressDrawable(module, cl)
        hookProgressText(module, cl)
    }

    private fun hookPlayerContainer(module: XposedModule, cl: ClassLoader) {
        // 原版 8.96.0 播放器容器是 be1.j(对应 8.98.0 patch 版的 Ch1.g,均为 PlayerContainer 子类)。
        // 注意用 smali 真实类名(小写 be1),不是 jadx 显示的 Be1。
        hookAfter(
            module,
            cl,
            "be1.j",
            "onCreate",
            Bundle::class.java,
        ) { chain ->
            val container = chain.getThisObject()
            ProbeLogger.dumpClassOnce(module, "player-container", container)

            // 容器创建时:绑定 core/context handle。director service 在 onCreate 时还是 null,
            // 需要在 onStart 时再注册 listener。
            val contextHash = PlayerBridge.contextHash(container)
            val core = PlayerBridge.coreService(container)
            if (contextHash != 0 && core != null) {
                val handle = PlayerHandle(contextHash, container, core)
                sponsorBlockController?.bindPlayerHandle(handle)
                sponsorBlockController?.let { controller ->
                    SubmissionButtonInjector.attach(module, container, controller, contextHash)
                }
            } else {
                module.info("player container missing core/context, skip binding context=$contextHash")
            }
            module.info("player container created")
        }

        hookAfter(
            module,
            cl,
            "be1.j",
            "onStart",
        ) { chain ->
            val container = chain.getThisObject()
            val contextHash = PlayerBridge.contextHash(container)

            // onStart 时 director service 已初始化,注册 VideoPlayEventListener 拿 aid/cid
            sponsorBlockController?.let { controller ->
                VideoDirectorListener.register(module, container) { aid, cid ->
                    controller.onVideoIds(contextHash, aid, cid)
                }
            }
            module.info("player container started, director listener registered")
        }

        hookAfter(
            module,
            cl,
            "be1.j",
            "onDestroy",
        ) { chain ->
            ProbeLogger.dumpClassOnce(module, "player-destroy", chain.getThisObject())
            module.info("player container destroyed")
        }
    }

    private fun hookProgressText(module: XposedModule, cl: ClassLoader) {
        // Hook 1: 在 onPlayerProgressChange 里缓存调整后的时长,用于后续 setText 拦截
        // 8.96.0 原版进度回调方法名是 onPlayerProgressChange(int position, int duration),
        // 不是 8.98.0 patch 版的 J(long,long)。三个进度文本类统一用 onPlayerProgressChange。
        hookAfter(
            module,
            cl,
            "com.bilibili.playerbizcommonv2.widget.base.PlayerProgressTextWidget",
            "onPlayerProgressChange",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ) { chain ->
            onProgressTextUpdate(module, chain)
        }

        hookAfter(
            module,
            cl,
            "com.bilibili.app.gemini.player.widget.progress.GeminiProgressTextWidget",
            "onPlayerProgressChange",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ) { chain ->
            onProgressTextUpdate(module, chain)
        }

        hookAfter(
            module,
            cl,
            "com.bilibili.playerbizcommon.widget.control.PlayerProgressTextWidget",
            "updateTime",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ) { chain ->
            onProgressTextUpdate(module, chain)
        }

        // Hook 2: 拦截 TextView.setText(),自动替换成调整后的文本
        hookProgressTextViewSetText(module, cl)
    }

    private fun hookProgressTextViewSetText(module: XposedModule, cl: ClassLoader) {
        // Hook 这三个进度文本类的 setText(CharSequence, BufferType)
        // 注意:setText 签名是 (CharSequence, TextView$BufferType),两个参数!
        val classNames = listOf(
            "com.bilibili.playerbizcommonv2.widget.base.PlayerProgressTextWidget",
            "com.bilibili.app.gemini.player.widget.progress.GeminiProgressTextWidget",
            "com.bilibili.playerbizcommon.widget.control.PlayerProgressTextWidget"
        )

        val bufferTypeClass = TextView.BufferType::class.java

        classNames.forEach { className ->
            // 用 before hook 修改 setText 的第一个参数(文本),从根源替换
            hookBeforeSetText(module, cl, className, bufferTypeClass)
        }
    }

    // 防止 setText 递归的标志
    private val isAdjusting = ThreadLocal.withInitial { false }

    private fun hookBeforeSetText(
        module: XposedModule,
        cl: ClassLoader,
        className: String,
        bufferTypeClass: Class<*>,
    ) {
        val method = findMethod(module, cl, className, "setText", CharSequence::class.java, bufferTypeClass) ?: run {
            module.info("skip missing setText hook: $className")
            return
        }

        module.hook(method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                // 先执行原方法(设置原始文本)
                val result = chain.proceed()

                // 防递归:如果是我们触发的 setText,跳过
                if (isAdjusting.get()) {
                    return@intercept result
                }

                val textView = chain.getThisObject() as? TextView ?: return@intercept result
                val originalText = textView.text ?: return@intercept result

                val newText = computeAdjustedText(originalText)
                if (newText != null && newText.toString() != originalText.toString()) {
                    isAdjusting.set(true)
                    try {
                        textView.text = newText
                    } finally {
                        isAdjusting.set(false)
                    }
                }
                result
            }
    }

    private fun computeAdjustedText(originalText: CharSequence): CharSequence? {
        // 已经带括号的不再处理(避免重复)
        val textStr = originalText.toString()
        if (textStr.contains("(") && textStr.contains(")")) {
            return null
        }

        val controller = sponsorBlockController ?: return null
        val (_, segments) = controller.latestSegments() ?: return null
        if (segments.isEmpty()) {
            return null
        }

        val durationMs = extractDurationFromText(textStr)
        if (durationMs <= 0) {
            return null
        }

        val adjustedDurationMs = RemainingTimeFormatter.adjustedDuration(durationMs, segments)
        if (adjustedDurationMs >= durationMs) {
            return null  // 没有可扣减的片段
        }

        return RemainingTimeFormatter.appendAdjustedDuration(originalText, adjustedDurationMs)
    }

    private fun extractDurationFromText(text: String): Long {
        // 尝试从 "00:16 / 30:01" 格式中提取总时长
        val regex = Regex("""(\d+):(\d+):(\d+)\s*/\s*(\d+):(\d+):(\d+)""")
        val match = regex.find(text)
        if (match != null) {
            val groups = match.groupValues
            val h = groups.getOrNull(4)?.toLongOrNull() ?: 0
            val m = groups.getOrNull(5)?.toLongOrNull() ?: 0
            val s = groups.getOrNull(6)?.toLongOrNull() ?: 0
            return (h * 3600 + m * 60 + s) * 1000
        }

        // 尝试 "00:16 / 30:01" 格式 (无小时)
        val regex2 = Regex("""(\d+):(\d+)\s*/\s*(\d+):(\d+)""")
        val match2 = regex2.find(text)
        if (match2 != null) {
            val groups = match2.groupValues
            val m = groups.getOrNull(3)?.toLongOrNull() ?: 0
            val s = groups.getOrNull(4)?.toLongOrNull() ?: 0
            return (m * 60 + s) * 1000
        }

        return -1L
    }

    private fun onProgressTextUpdate(module: XposedModule, chain: io.github.libxposed.api.XposedInterface.Chain) {
        val target = chain.getThisObject() ?: return
        val args = chain.getArgs()
        val positionMs = (args.getOrNull(0) as? Number)?.toLong() ?: return
        val durationMs = (args.getOrNull(1) as? Number)?.toLong() ?: return

        // onProgressTextUpdate 只负责触发自动跳过。
        // 时长扣减显示完全交给 setText hook (hookBeforeSetText),
        // 因为 B 站显示/隐藏进度条时会重新 setText,只有拦截 setText 才能持久生效。
        sponsorBlockController?.let { controller ->
            val contextHash = controller.latestContextHash
            controller.onProgress(contextHash, positionMs, durationMs)
        }
    }

    private fun hookProgressDrawable(module: XposedModule, cl: ClassLoader) {
        // 8.96.0 原版 seekbar process drawable 是 seek.v3.a(对应 8.98.0 patch 版的 seek.v3.f)。
        hookAfter(
            module,
            cl,
            "com.bilibili.playerbizcommonv2.widget.seek.v3.a",
            "draw",
            Canvas::class.java,
        ) { chain ->
            val drawable = chain.getThisObject() as? android.graphics.drawable.Drawable ?: return@hookAfter
            val canvas = chain.getArgs().getOrNull(0) as? Canvas ?: return@hookAfter
            ProbeLogger.dumpClassOnce(module, "seekbar-draw", drawable)
            val markers = sponsorBlockController?.progressMarkers()
            if (markers == null) {
                return@hookAfter
            }
            val (durationMs, segments) = markers
            ProgressMarkerPainter.draw(drawable, canvas, durationMs, segments)
        }
    }

    private fun hookAfter(
        module: XposedModule,
        cl: ClassLoader,
        className: String,
        methodName: String,
        vararg paramTypes: Class<*>,
        onAfter: (io.github.libxposed.api.XposedInterface.Chain) -> Unit,
    ) {
        val method = findMethod(module, cl, className, methodName, *paramTypes) ?: run {
            module.info("skip missing hook target: $className#$methodName(${paramTypes.joinToString { it.name }})")
            return
        }

        module.hook(method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                onAfter(chain)
                result
            }
    }

    private fun findMethod(
        module: XposedModule,
        cl: ClassLoader,
        className: String,
        methodName: String,
        vararg paramTypes: Class<*>,
    ): Method? {
        return runCatching {
            Class.forName(className, false, cl).getDeclaredMethod(methodName, *paramTypes).apply {
                isAccessible = true
            }
        }.onFailure { throwable ->
            module.info("failed to resolve $className#$methodName: ${throwable.javaClass.name}: ${throwable.message}")
        }.getOrNull()
    }
}
