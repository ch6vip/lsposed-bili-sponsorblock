package com.ctf.bilisb

import android.graphics.Canvas
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.ctf.bilisb.player.PlayerBridge
import com.ctf.bilisb.sponsor.SponsorBlockController
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

    private fun hookProgressDrawable(module: XposedModule, cl: ClassLoader) {
        hookAfter(
            module,
            cl,
            "com.bilibili.playerbizcommonv2.widget.seek.v3.f",
            "draw",
            Canvas::class.java,
        ) { chain ->
            ProbeLogger.dumpClassOnce(module, "seekbar-draw", chain.getThisObject())
            module.info("seekbar draw")
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
