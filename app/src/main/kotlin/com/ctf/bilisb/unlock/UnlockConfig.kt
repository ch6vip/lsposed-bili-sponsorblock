package com.ctf.bilisb.unlock

import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicReference

/**
 * 解锁功能的运行配置（U4 最小形态，U7 并入设置管线）。
 *
 * 读取设置镜像 JSON 的 `unlock_*` 键（直接读文件，不走 IPC——开发期足够，
 * 与 EnhanceFlags 的 TTL 缓存同思路）：
 *
 *  - `unlock_enabled`: bool，总开关，**默认 false**（G1/G2 完成前不随版本发布）
 *  - `unlock_servers`: string，JSON 数组 `[{"area":"cn","baseUrl":"http://…","accessKey":"…"}]`
 *  - `unlock_test_epid`: long，**开发专用**——命中该 ep_id 的正常 PGC 请求被强制按受限处理，
 *    用于在没有已知受限样本时验证闭环（U7 评估去留）
 *
 * 服务器与 accessKey 由用户自配（自建或社区），模块不含内置服务器。
 */
object UnlockConfig {

    data class Config(
        val enabled: Boolean,
        val servers: List<RoamingClient.RoamingServer>,
        val testEpId: Long,
    )

    private val cache = AtomicReference(Pair(0L, Config(false, emptyList(), 0)))
    private val lastAreaRef = AtomicReference<String?>(null)

    private const val TTL_MS = 60_000L

    fun load(module: XposedModule): Config {
        val now = android.os.SystemClock.uptimeMillis()
        cache.get().let { (at, cfg) -> if (now - at < TTL_MS) return cfg }
        val cfg = readFromMirror(module)
        cache.set(now to cfg)
        return cfg
    }

    /** 上次成功使用的区域（进程内记忆，跨集连播时减少试错）。 */
    fun lastArea(): String? = lastAreaRef.get()

    fun rememberArea(area: String?) {
        area?.let { lastAreaRef.set(it) }
    }

    private fun readFromMirror(module: XposedModule): Config {
        return runCatching {
            val file = java.io.File(HostTargets.HOST_DATA_DIRS.first(), "sponsorblock_settings.json")
            if (!file.isFile) return@runCatching Config(false, emptyList(), 0)
            val json = org.json.JSONObject(file.readText())
            val enabled = json.optBoolean("unlock_enabled", false)
            val testEpId = json.optLong("unlock_test_epid", 0L)
            val servers = mutableListOf<RoamingClient.RoamingServer>()
            val arr = runCatching {
                org.json.JSONArray(json.optString("unlock_servers", "[]"))
            }.getOrNull()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val baseUrl = s.optString("baseUrl").trim()
                    if (baseUrl.isEmpty()) continue
                    servers += RoamingClient.RoamingServer(
                        area = s.optString("area", "cn"),
                        baseUrl = baseUrl,
                        accessKey = s.optString("accessKey", ""),
                    )
                }
            }
            Config(enabled, servers, testEpId)
        }.onFailure { t ->
            module.warn("unlock: config read failed: ${t.message}")
        }.getOrDefault(Config(false, emptyList(), 0))
    }
}
