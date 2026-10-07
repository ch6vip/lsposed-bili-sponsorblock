package com.ctf.bilisb.unlock

import android.net.Uri
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.util.info
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.Collection

/**
 * 播放器级 UPOS / PCDN 替换钩（对齐 BiliRoaming C0161h6.o / Fe(17)）。
 *
 * 作用于 `tv.danmaku.ijk.media.player.IjkMediaAsset$MediaAssertSegment$Builder`：
 * 构造单段分段时将所有普通视频（含 UGC、番剧、电影等）的 URL host 替换为用户选定的 UPOS，
 * 并过滤/净化 PCDN 与备用地址，达到「应用 UPOS 到所有视频」的效果。
 */
object IjkPlayerUposHook {

    private const val BUILDER_CLASS = "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssertSegment\$Builder"
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun install(module: XposedModule, cl: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            val builderCls = cl.loadClass(BUILDER_CLASS)
            // 构造器：Builder(String url, int duration)
            for (ctor in builderCls.declaredConstructors) {
                if (ctor.parameterTypes.size >= 2 && ctor.parameterTypes[0] == String::class.java) {
                    runCatching { ctor.isAccessible = true }
                    runCatching { module.deoptimize(ctor) }
                    module.hook(ctor)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val config = UnlockConfig.load(module)
                            if (config.enabled && config.forceUpos && config.uposHost.isNotBlank()) {
                                val origUrl = chain.args.getOrNull(0) as? String
                                if (origUrl != null) {
                                    val rewritten = rewriteMediaUrl(origUrl, config.uposHost)
                                    if (rewritten != origUrl) {
                                        HookProbe.first(module, "unlock:forceUposApplied", 3) {
                                            "ijk segment url replaced -> ${config.uposHost}"
                                        }
                                        val newArgs = chain.args.toTypedArray()
                                        newArgs[0] = rewritten
                                        return@intercept chain.proceed(newArgs)
                                    }
                                }
                            }
                            chain.proceed()
                        }
                    HookProbe.ok(module, "unlock:ijkUposCtor", "hooked Builder constructor")
                }
            }

            // setBackupUrls(Collection urls)
            for (m in builderCls.declaredMethods) {
                if (m.name == "setBackupUrls" && m.parameterTypes.size == 1 &&
                    Collection::class.java.isAssignableFrom(m.parameterTypes[0])
                ) {
                    runCatching { m.isAccessible = true }
                    runCatching { module.deoptimize(m) }
                    module.hook(m)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val config = UnlockConfig.load(module)
                            if (config.enabled && config.forceUpos && config.uposHost.isNotBlank()) {
                                val origList = chain.args.getOrNull(0) as? Collection<*>
                                if (origList != null) {
                                    val filtered = origList.mapNotNull { it as? String }
                                        .filterNot { UposReplacer.isPcdnUrl(it) }
                                        .map { rewriteMediaUrl(it, config.uposHost) }
                                    val newArgs = chain.args.toTypedArray()
                                    newArgs[0] = filtered
                                    return@intercept chain.proceed(newArgs)
                                }
                            }
                            chain.proceed()
                        }
                    HookProbe.ok(module, "unlock:ijkUposBackup", "hooked setBackupUrls")
                }
            }
        }.onFailure { t ->
            HookProbe.first(module, "unlock:ijkUposMiss", 1) {
                "IjkMediaAsset builder not found: ${t.message}"
            }
        }
    }

    private fun extractQueryParam(url: String, key: String): String? {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return null
        val query = url.substring(qIdx + 1)
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq) == key) {
                return part.substring(eq + 1)
            }
        }
        return null
    }

    /**
     * 重写视频片段 URL：替换 host 为指定 UPOS，并追加/替换 bw=1280000 避免限速。
     */
    fun rewriteMediaUrl(url: String, uposHost: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        if (url.contains("live-bvc")) return url // 直播特殊通道不改动

        var targetHost = uposHost
        // 对齐 BiliRoaming: xy_usource 参数如果存在，可作为优先 host
        val xyUsource = extractQueryParam(url, "xy_usource")
        if (!xyUsource.isNullOrBlank() && !UposReplacer.isPcdnUrl(xyUsource)) {
            targetHost = xyUsource
        }

        // PCDN 网关形态直接替换可能失败，按纯净 UPOS host 替换
        val replaced = UposReplacer.replaceHost(url, targetHost)
        // 破除带宽限制：确保存在 bw=1280000
        return if (replaced.contains("bw=")) {
            replaced.replace(Regex("""bw=\d+"""), "bw=1280000")
        } else {
            val sep = if (replaced.contains('?')) "&" else "?"
            "$replaced${sep}bw=1280000"
        }
    }
}
