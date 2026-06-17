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
    private var settings: com.ctf.bilisb.settings.SettingsSnapshot = com.ctf.bilisb.settings.SettingsSnapshot.DEFAULT

    fun install(module: XposedModule, param: PackageLoadedParam, processName: String) {
        val installKey = "${param.packageName}:$processName"
        if (!installed.add(installKey)) {
            return
        }

        val cl = param.defaultClassLoader
        module.info("Installing hooks for ${param.packageName} process=$processName with $cl")

        // 注意:此时 Application 尚未创建,无法获取 Context 读取设置。
        // 设置加载延迟到 hookPlayerContainer 里的 be1.j.onCreate(),
        // 那时容器是 View,可以通过 getContext() 拿到 Context 进行 ContentProvider IPC。
        hookPlayerContainer(module, cl)
        hookProgressDrawable(module, cl)
        hookProgressText(module, cl)

        // 注入"我的"页面菜单设置入口
        com.ctf.bilisb.hook.MineMenuInjector.install(module, cl)
    }

    private fun ensureSettingsLoaded(module: XposedModule, containerContext: android.content.Context) {
        val freshSettings = runCatching {
            com.ctf.bilisb.settings.ModuleSettings.reload(module, containerContext)
        }.getOrElse {
            module.info("ModuleSettings reload failed, using defaults: ${it.message}")
            com.ctf.bilisb.settings.SettingsSnapshot.DEFAULT
        }
        settings = freshSettings
        module.info("Settings snapshot on player enter: $freshSettings")
        if (!freshSettings.enabled) {
            sponsorBlockController = null
            module.info("SponsorBlock disabled in settings")
            return
        }
        sponsorBlockController = SponsorBlockController(module, freshSettings)
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

            // 延迟加载设置:此时 Application 已创建,通过反射获取 Context
            val hostContext = runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? android.content.Context
            }.getOrNull() ?: runCatching {
                // 兜底:从容器对象的字段里找 Context(探针显示 be1.j 有 b:Context 字段)
                container.javaClass.declaredFields.firstOrNull {
                    android.content.Context::class.java.isAssignableFrom(it.type)
                }?.apply { isAccessible = true }?.get(container) as? android.content.Context
            }.getOrNull()

            if (hostContext != null) {
                ensureSettingsLoaded(module, hostContext)
            } else {
                module.info("Cannot obtain Context for settings, using defaults")
            }

            // 容器创建时:绑定 core/context handle。director service 在 onCreate 时还是 null,
            // 需要在 onStart 时再注册 listener。
            val contextHash = PlayerBridge.contextHash(container)
            val core = PlayerBridge.coreService(container)
            if (contextHash != 0 && core != null) {
                val handle = PlayerHandle(contextHash, container, core)
                sponsorBlockController?.bindPlayerHandle(handle)
                if (settings.showSubmitButton) {
                    sponsorBlockController?.let { controller ->
                        SubmissionButtonInjector.attach(module, container, controller, contextHash, settings.defaultSubmitCategory)
                    }
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
            val container = chain.getThisObject()
            // 播放器销毁时取消我们触发的静音,避免静音泄漏到其它媒体。
            sponsorBlockController?.onPlayerDestroyed(container)
            ProbeLogger.dumpClassOnce(module, "player-destroy", container)
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
                if (isAdjusting.get() == true) {
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

    // 运行时探针实测（8.96.0 Gemini，PlayerSeekWidget3）得到的薄轨道 drawable：
    //   mProgressDrawable / mCurrentDrawable = seek.v3.q（h=8，ProgressBar.onDraw 必然绘制）
    //   manager i.B[0..2] = seek.v3.e ×3（h=8，背景/缓冲/进度三层）
    //   而之前 hook 的 seek.v3.a 是 h=36 的高能热度曲线波形，画在它上面才会偏高。
    // 把标记画在这些 h=8 的薄轨道 bounds 上 = 嵌入式，对齐官方 patch（patch 在 8.98.0 上对应的是 seek.v3.f）。
    // 两个候选都挂：哪个类声明了 draw(Canvas) 就生效；都画同样的实色矩形（同像素，无副作用）。
    private val seekbarTrackClasses = listOf(
        "com.bilibili.playerbizcommonv2.widget.seek.v3.q",
        "com.bilibili.playerbizcommonv2.widget.seek.v3.e",
    )
    private val markerHookLogged = ConcurrentHashMap.newKeySet<String>()

    private fun hookProgressDrawable(module: XposedModule, cl: ClassLoader) {
        seekbarTrackClasses.forEach { className ->
            hookAfter(
                module,
                cl,
                className,
                "draw",
                Canvas::class.java,
            ) { chain ->
                val drawable = chain.getThisObject() as? android.graphics.drawable.Drawable ?: return@hookAfter
                val canvas = chain.getArgs().getOrNull(0) as? Canvas ?: return@hookAfter

                if (!settings.showSeekbarMarker) {
                    return@hookAfter
                }

                val markers = sponsorBlockController?.progressMarkers() ?: return@hookAfter
                val (durationMs, segments) = markers
                if (segments.isEmpty()) {
                    return@hookAfter
                }

                if (markerHookLogged.add(className)) {
                    val b = drawable.bounds
                    module.info("marker-hook fired on $className bounds=$b h=${b.height()}")
                }
                ProgressMarkerPainter.draw(drawable, canvas, durationMs, segments, settings.categoryColors)
            }
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
