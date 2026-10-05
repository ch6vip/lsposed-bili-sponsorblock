package com.ctf.bilisb

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.util.info

/**
 * 模块入口。
 *
 * 目标宿主：`com.bilibili.app.in`（bilibili 6.6.0）。
 * 主进程挂全套 Hook；`:download`（下载引擎）只挂「取播放地址」最小集——它自己取一次地址，
 * 不挂则缓存任务必失败；`:web` / `:pushservice` / `:ijkservice` 等其余子进程直接跳过
 * （既无播放器 UI 也无下载引擎，挂上去只会增加崩溃面）。
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
            if (processName.endsWith(HostTargets.DOWNLOAD_PROCESS)) {
                info("Install download-process hooks: $processName (knownSubProcess=$known)")
                BiliSponsorBlockHooks.installForDownloadProcess(this, param, processName)
            } else {
                info("Skip hooks in non-main process: $processName (knownSubProcess=$known)")
            }
            return
        }

        BiliSponsorBlockHooks.install(this, param, processName)
    }
}
