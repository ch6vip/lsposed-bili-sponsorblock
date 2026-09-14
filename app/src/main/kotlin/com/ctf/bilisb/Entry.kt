package com.ctf.bilisb

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.util.info

/**
 * 模块入口。
 *
 * 目标宿主：`com.bilibili.app.in`（bilibili 6.5.0）。
 * 只在宿主**主进程**挂 Hook；`:web` / `:download` / `:pushservice` / `:ijkservice` 等子进程直接跳过
 * （子进程里没有播放器 UI，挂上去只会增加崩溃面）。
 */
class Entry : XposedModule() {
    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        info("Bili2233 module loaded in ${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != HostTargets.HOST_PACKAGE) {
            return
        }

        if (processName != HostTargets.HOST_PACKAGE) {
            val known = HostTargets.HOST_SUB_PROCESSES.any { processName.endsWith(it) }
            info("Skip hooks in non-main process: $processName (knownSubProcess=$known)")
            return
        }

        BiliSponsorBlockHooks.install(this, param, processName)
    }
}
