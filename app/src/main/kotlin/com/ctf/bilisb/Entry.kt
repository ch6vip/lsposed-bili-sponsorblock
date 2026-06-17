package com.ctf.bilisb

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.ctf.bilisb.util.info

class Entry : XposedModule() {
    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        info("Bili2233 module loaded in ${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) {
            return
        }

        if (processName != TARGET_PACKAGE) {
            info("Skip hooks in non-main process: $processName")
            return
        }

        BiliSponsorBlockHooks.install(this, param, processName)
    }

    private companion object {
        const val TARGET_PACKAGE = "tv.danmaku.bili"
    }
}
