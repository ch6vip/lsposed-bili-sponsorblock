package com.ctf.bilisb

import android.graphics.Canvas
import android.os.Bundle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object BiliSponsorBlockHooks {
    private val installed = ConcurrentHashMap.newKeySet<String>()

    fun install(module: XposedModule, param: PackageLoadedParam) {
        if (!installed.add(param.packageName)) {
            return
        }

        val cl = param.defaultClassLoader
        module.log("Installing hooks for ${param.packageName} with $cl")

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
        ) {
            module.log("player container created")
        }
    }

    private fun hookProgressDrawable(module: XposedModule, cl: ClassLoader) {
        hookAfter(
            module,
            cl,
            "com.bilibili.playerbizcommonv2.widget.seek.v3.f",
            "draw",
            Canvas::class.java,
        ) {
            module.log("seekbar draw")
        }
    }

    private fun hookAfter(
        module: XposedModule,
        cl: ClassLoader,
        className: String,
        methodName: String,
        vararg paramTypes: Class<*>,
        onAfter: () -> Unit,
    ) {
        val method = findMethod(cl, className, methodName, *paramTypes) ?: run {
            module.log("skip missing hook target: $className#$methodName")
            return
        }

        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            onAfter()
            result
        }
    }

    private fun findMethod(
        cl: ClassLoader,
        className: String,
        methodName: String,
        vararg paramTypes: Class<*>,
    ): Method? {
        return runCatching {
            Class.forName(className, false, cl).getDeclaredMethod(methodName, *paramTypes).apply {
                isAccessible = true
            }
        }.getOrNull()
    }
}
