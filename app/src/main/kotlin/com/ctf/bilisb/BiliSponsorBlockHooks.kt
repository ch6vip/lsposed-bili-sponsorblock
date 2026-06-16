package com.ctf.bilisb

import android.graphics.Canvas
import android.os.Bundle
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.ctf.bilisb.player.PlayerBridge
import com.ctf.bilisb.player.PlayerHandle
import com.ctf.bilisb.sponsor.SponsorBlockController
import com.ctf.bilisb.ui.ProgressTextDecorator
import com.ctf.bilisb.ui.ProgressMarkerPainter
import com.ctf.bilisb.ui.RemainingTimeFormatter
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
        hookAfter(
            module,
            cl,
            "Ch1.g",
            "onCreate",
            Bundle::class.java,
        ) { chain ->
            val container = chain.getThisObject()
            ProbeLogger.dumpClassOnce(module, "player-container", container)
            PlayerBridge.extractState(module, container)?.let { state ->
                module.info(
                    "player state aid=${state.aid} cid=${state.cid} bvid=${state.bvid} " +
                        "position=${state.currentPositionMs} duration=${state.durationMs}",
                )
                sponsorBlockController?.onPlayerState(state)
                val contextHash = PlayerBridge.contextHash(container)
                val core = PlayerBridge.coreService(container)
                if (contextHash != 0 && core != null) {
                    sponsorBlockController?.bindPlayerHandle(
                        PlayerHandle(contextHash, container, core),
                        state,
                    )
                }
            }
            module.info("player container created")
        }

        hookAfter(
            module,
            cl,
            "Ch1.g",
            "onDestroy",
        ) { chain ->
            ProbeLogger.dumpClassOnce(module, "player-destroy", chain.getThisObject())
            module.info("player container destroyed")
        }
    }

    private fun hookProgressText(module: XposedModule, cl: ClassLoader) {
        hookAfter(
            module,
            cl,
            "com.bilibili.playerbizcommonv2.widget.base.PlayerProgressTextWidget",
            "J",
            Long::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
        ) { chain ->
            onProgressTextUpdate(chain)
        }

        hookAfter(
            module,
            cl,
            "com.bilibili.app.gemini.player.widget.progress.GeminiProgressTextWidget",
            "K",
            Long::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
        ) { chain ->
            onProgressTextUpdate(chain)
        }

        hookAfter(
            module,
            cl,
            "com.bilibili.playerbizcommon.widget.control.PlayerProgressTextWidget",
            "updateTime",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ) { chain ->
            onProgressTextUpdate(chain)
        }
    }

    private fun onProgressTextUpdate(chain: io.github.libxposed.api.XposedInterface.Chain) {
        val target = chain.getThisObject() ?: return
        val args = chain.getArgs()
        val positionMs = (args.getOrNull(0) as? Number)?.toLong() ?: return
        val durationMs = (args.getOrNull(1) as? Number)?.toLong() ?: return
        val contextHash = target.hashCode()
        sponsorBlockController?.onProgress(contextHash, positionMs, durationMs)
        val segments = sponsorBlockController?.segmentsForContext(contextHash) ?: return
        val textView = target as? TextView ?: return
        if (ProgressTextDecorator.isSameDecoration(textView, textView.text)) {
            return
        }
        val adjustedDurationMs = RemainingTimeFormatter.adjustedDuration(durationMs, segments)
        textView.text = ProgressTextDecorator.applyIfNeeded(textView, adjustedDurationMs, textView.text)
    }

    private fun hookProgressDrawable(module: XposedModule, cl: ClassLoader) {
        hookAfter(
            module,
            cl,
            "com.bilibili.playerbizcommonv2.widget.seek.v3.f",
            "draw",
            Canvas::class.java,
        ) { chain ->
            val drawable = chain.getThisObject() as? android.graphics.drawable.Drawable ?: return@hookAfter
            val canvas = chain.getArgs().getOrNull(0) as? Canvas ?: return@hookAfter
            ProbeLogger.dumpClassOnce(module, "seekbar-draw", drawable)
            sponsorBlockController?.progressMarkers()?.let { (durationMs, segments) ->
                ProgressMarkerPainter.draw(drawable, canvas, durationMs, segments)
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
