package com.ctf.bilisb.unlock

import com.ctf.bilisb.host.HookProbe
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * 国际版数据通道透明出口（方案 A 第②步）：把 `*.biliintl.com`（app/grpc 等国际版端点）
 * 的解析劫持到本机（IP 取 unlock_server_url 的 host），由 PC 端 relay.py（443 监听，
 * 读 ClientHello SNI → 台湾出口拨真实主机）透明搬运字节。App 的 TLS 端到端对真实主机，
 * 证书校验/锁定不受影响。
 *
 * 背景（2026-10-02 交接文档 §4）：大陆网络下 Akamai/103.151.x 对国际版端点全不可达，
 * 宿主整个国际版数据通道是死的——选集区块不渲染、SeasonMoss 六方法无人调用。
 *
 * 两层挂钩（2026-10-04 真机实证：InetAddress 层对 6.6.0 宿主**零命中**——宿主数据通道
 * 走自有 DNS 栈 `com.bilibili.ignetdns.IgHttpDns`（HTTPDNS + JNI native_resolve），
 * 根本不经过 java.net.InetAddress）：
 *  1. `InetAddress.getAllByName/getByName`——兜底（okhttp 默认 Dns 等仍走这里）；
 *  2. `IgHttpDns.resolve/resolveSync` 返回值改写——主路径，重建宿主 Record（ips 换中继 IP，
 *     其余字段原样；原结果为 null 时全字段自造）。
 *
 * 仅当 unlock_enabled 且配置了服务器时改写；其余域名一律透传。
 */
object BiliIntlDnsHook {

    private const val SUFFIX = "biliintl.com"

    private const val IGET_DNS_CLASS = "com.bilibili.ignetdns.IgHttpDns"
    private const val RECORD_CLASS = "com.bilibili.ignetdns.Record"

    /** baseUrl → 中继 IP 字节（config TTL 60s 内不重复 parse URI/解析）。 */
    private val relayIpByBaseUrl = ConcurrentHashMap<String, ByteArray>()

    fun install(module: XposedModule, cl: ClassLoader) {
        installInetAddress(module)
        installIgnetDns(module, cl)
    }

    // ---- 第 1 层：java.net.InetAddress（兜底路径）----

    private fun installInetAddress(module: XposedModule) {
        val ia = InetAddress::class.java
        val hooked = mutableListOf<String>()

        // getAllByName(String)：OkHttp Dns 默认实现的唯一入口
        runCatching {
            hookOne(module, ia.getDeclaredMethod("getAllByName", String::class.java), all = true)
            hooked += "getAllByName(String)"
        }.onFailure { HookProbe.miss(module, "biliIntlDns", "getAllByName(String): ${it.message}") }

        runCatching {
            hookOne(module, ia.getDeclaredMethod("getByName", String::class.java), all = false)
            hooked += "getByName(String)"
        }.onFailure { HookProbe.miss(module, "biliIntlDns", "getByName(String): ${it.message}") }

        // 部分平台隐藏重载 getAllByName(String, int netId)，存在则一并挂
        runCatching {
            val m = ia.getDeclaredMethod("getAllByName", String::class.java, Int::class.javaPrimitiveType)
            hookOne(module, m, all = true)
            hooked += "getAllByName(String,int)"
        }

        if (hooked.isEmpty()) {
            HookProbe.miss(module, "biliIntlDns", "no InetAddress overloads hookable")
        } else {
            HookProbe.ok(module, "biliIntlDns", hooked.joinToString(", "))
        }
    }

    private fun hookOne(module: XposedModule, m: Method, all: Boolean) {
        module.hook(m)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val host = (chain.args.getOrNull(0) as? String)?.trim()?.lowercase()
                    ?: return@intercept chain.proceed()
                if (host != SUFFIX && !host.endsWith(".$SUFFIX")) {
                    return@intercept chain.proceed()
                }
                val ip = relayIpString(module) ?: run {
                    HookProbe.first(module, "biliIntlDns:noIp", 3) {
                        "无法取得中继 IP，透传 $host"
                    }
                    return@intercept chain.proceed()
                }
                // getByAddress(host, bytes)：不发起 DNS，直接按字节构地址
                val addr = InetAddress.getByAddress(host, relayIpBytes(module)!!)
                HookProbe.first(module, "biliIntlDns:rewrite", 5) {
                    "$host → $ip"
                }
                if (all) arrayOf(addr) else addr
            }
    }

    // ---- 第 2 层：宿主自有 DNS 栈 IgHttpDns（6.6.0 数据通道主路径）----

    private fun installIgnetDns(module: XposedModule, cl: ClassLoader) {
        runCatching {
            val dnsCls = Class.forName(IGET_DNS_CLASS, false, cl)
            val recordCls = Class.forName(RECORD_CLASS, false, cl)
            val ctor = recordCls.getConstructor(
                String::class.java, String::class.java, String::class.java, String::class.java,
                Array<String>::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            )
            val hooked = mutableListOf<String>()
            for (name in listOf("resolve", "resolveSync")) {
                val m = runCatching { dnsCls.getDeclaredMethod(name, String::class.java) }
                    .getOrNull() ?: continue
                runCatching { m.isAccessible = true }
                runCatching { module.deoptimize(m) }
                module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        rewriteIgnetRecord(module, ctor, chain.args.getOrNull(0), result)
                    }
                hooked += name
            }
            if (hooked.isEmpty()) {
                HookProbe.miss(module, "biliIntlDnsIgnet", "resolve/resolveSync 均不存在")
            } else {
                HookProbe.ok(module, "biliIntlDnsIgnet", hooked.joinToString(", "))
            }
        }.onFailure { t ->
            HookProbe.miss(module, "biliIntlDnsIgnet", "install: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** resolve/resolveSync 的 after 改写：biliintl 域名 → ips 换中继 IP 的 Record。 */
    private fun rewriteIgnetRecord(
        module: XposedModule,
        ctor: java.lang.reflect.Constructor<*>,
        argHost: Any?,
        result: Any?,
    ): Any? {
        val host = (argHost as? String)?.trim()?.lowercase() ?: return result
        val isIntl = host == SUFFIX || host.endsWith(".$SUFFIX")
        // 观测：主路径是否真的经过此栈（intl 域名与其他域名分开留痕）
        HookProbe.first(module, "biliIntlDns:ignet${if (isIntl) "Intl" else "Other"}", 3) {
            "$host -> ${result?.javaClass?.simpleName ?: "null"}"
        }
        if (!isIntl) return result
        val config = UnlockConfig.load(module)
        if (!config.enabled || config.servers.isEmpty()) {
            HookProbe.first(module, "biliIntlDns:passthrough", 3) {
                "$host 解析透传（unlock 未启用）"
            }
            return result
        }
        val ip = relayIpString(module) ?: return result
        val newRecord = runCatching {
            // Record(provider, host, clientIp, clientISP, ips, ttl, originTtl)
            if (result != null) {
                val r = result
                fun str(name: String) =
                    r.javaClass.fields.firstOrNull { it.name == name }?.get(r) as? String ?: ""
                fun lng(name: String) =
                    (r.javaClass.fields.firstOrNull { it.name == name }?.get(r) as? Long) ?: 60L
                ctor.newInstance(
                    str("provider"), r.javaClass.fields.firstOrNull { it.name == "host" }?.get(r) ?: host,
                    str("clientIp"), str("clientISP"),
                    arrayOf(ip), lng("ttl"), lng("originTtl"),
                )
            } else {
                ctor.newInstance("bilisb", host, "", "", arrayOf(ip), 60L, 60L)
            }
        }.getOrNull()
        if (newRecord != null) {
            HookProbe.first(module, "biliIntlDns:rewrite", 5) { "$host → $ip (ignet)" }
        }
        return newRecord ?: result
    }

    // ---- 中继 IP 解析（两层共用）----

    /** 从 unlock_server_url 取中继 IP 点分串；host 本身是 biliintl 域名时拒绝（防自指环）。 */
    private fun relayIpString(module: XposedModule): String? {
        val bytes = relayIpBytes(module) ?: return null
        return bytes.joinToString(".")
    }

    private fun relayIpBytes(module: XposedModule): ByteArray? {
        val config = UnlockConfig.load(module)
        if (config.enabled.not() || config.servers.isEmpty()) return null
        val baseUrl = config.servers.first().baseUrl
        relayIpByBaseUrl[baseUrl]?.let { return it }
        val host = runCatching { URI(baseUrl).host }.getOrNull()
            ?: baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host == SUFFIX || host.endsWith(".$SUFFIX")) return null
        val bytes = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return null
        relayIpByBaseUrl[baseUrl] = bytes
        return bytes
    }
}
