package com.ctf.bilisb.hook

import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.settings.EnhanceFlags
import com.ctf.bilisb.settings.SettingsSanitizer
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.net.URI

/**
 * 宿主进程放行自定义 SponsorBlock 实例的明文 HTTP。
 *
 * 模块 APK 的 network_security_config 只约束模块进程。
 * 片段拉取/提交跑在 B 站进程里,走宿主 NSC。只对用户填的 http host 放行,
 * 不碰无参 isCleartextTrafficPermitted(那会放开整个宿主的明文流量)。
 *
 * Note: 宿主 cleartext 必须按 host 放行 — 见 .agents/notes/implemented/bug-fix/2026-03-22-full-audit-round2.md
 */
object CleartextPolicyHooks {
    fun install(module: XposedModule, cl: ClassLoader) {
        val policy = runCatching {
            Class.forName("android.security.NetworkSecurityPolicy", false, cl)
        }.getOrNull() ?: run {
            HookProbe.skip(module, "cleartextPolicy", "NetworkSecurityPolicy absent (API < 24)")
            return
        }
        val method = policy.methods.firstOrNull { m ->
            m.name == "isCleartextTrafficPermitted" &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == String::class.java
        } ?: run {
            HookProbe.miss(module, "cleartextPolicy", "isCleartextTrafficPermitted(String) not found")
            return
        }
        runCatching { module.deoptimize(method) }
        module.hook(method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val hostname = chain.getArgs().getOrNull(0) as? String
                if (shouldPermit(module, hostname)) true else chain.proceed()
            }
        HookProbe.ok(module, "cleartextPolicy", "NetworkSecurityPolicy#isCleartextTrafficPermitted(String)")
    }

    internal fun shouldPermit(module: XposedModule, hostname: String?): Boolean {
        val address = runCatching { EnhanceFlags.snapshot(module).serverAddress }.getOrNull() ?: return false
        return hostMatches(address, hostname)
    }

    /** 纯函数:只放行「当前自定义 http 实例」的 host,https / 非法地址一律 false。 */
    internal fun hostMatches(serverAddress: String, hostname: String?): Boolean {
        if (hostname.isNullOrBlank()) return false
        if (!serverAddress.startsWith("http://", ignoreCase = true)) return false
        if (!SettingsSanitizer.isValidServerAddress(serverAddress)) return false
        val allowed = runCatching { URI(serverAddress).host }.getOrNull() ?: return false
        return hostname.equals(allowed, ignoreCase = true)
    }
}
