package com.ctf.bilisb

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class Entry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log("BiliSponsorBlock module loaded in ${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) {
            return
        }

        BiliSponsorBlockHooks.install(this, param)
    }

    private companion object {
        const val TARGET_PACKAGE = "tv.danmaku.bili"
    }
}
