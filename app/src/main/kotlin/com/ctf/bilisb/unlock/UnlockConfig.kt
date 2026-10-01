package com.ctf.bilisb.unlock

import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicReference

/**
 * 解锁功能的运行配置（U4 最小形态，U7 并入设置管线）。
 *
 * 读取设置镜像 JSON 的 `unlock_*` 键（直接读文件 + TTL 缓存，与 EnhanceFlags 同思路；
 * 这些键自 U7 起进入正式设置管线，控制中心「解锁番剧」页写入）：
 *
 *  - `unlock_enabled`: bool，总开关，**默认 false**（G1/G2 完成前不随版本发布）
 *  - `unlock_server_url`: string，解析服务器地址（用户自配，自建或社区）
 *  - `unlock_server_access_key`: string，服务器访问令牌
 *  - `unlock_cache`: bool，缓存解锁
 *  - `unlock_upos_host`: string，CDN 替换目标 host
 *  - `unlock_test_epid`: long，**开发专用**——命中该 ep_id 的正常 PGC 请求被强制按受限
 *    处理，用于在没有已知受限样本时验证闭环（U8 评估去留，不进设置 UI）
 */
object UnlockConfig {

    data class Config(
        val enabled: Boolean,
        val servers: List<RoamingClient.RoamingServer>,
        val testEpId: Long,
        /** 缓存解锁：请求补参 fnval 拉满 + download=0（U6）。 */
        val cacheUnlock: Boolean,
        /** CDN upos 替换目标 host（空 = 不替换，U5）。 */
        val uposHost: String,
        /** U4.6 透传诊断：重构时保留原 vod_info（隔离合成 vodInfo 变量）。 */
        val passthrough: Boolean,
    ) {
        companion object {
            val DEFAULT = Config(false, emptyList(), 0, false, "", false)
        }
    }

    private val cache = AtomicReference(Pair(0L, Config.DEFAULT))
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
            if (!file.isFile) return@runCatching Config.DEFAULT
            val json = org.json.JSONObject(file.readText())
            val enabled = json.optBoolean("unlock_enabled", false)
            val testEpId = json.optLong("unlock_test_epid", 0L)
            val cacheUnlock = json.optBoolean("unlock_cache", false)
            val uposHost = json.optString("unlock_upos_host", "")
            val passthrough = json.optBoolean("unlock_passthrough", false)
            // 单服务器模型：area 可配（cn/hk/tw/th——决定服务器转发到哪个上游；
            // 国际版 App 的令牌配 th 走国际网关，国内版令牌配 cn 走国内 API）
            val serverUrl = json.optString("unlock_server_url", "").trim()
            val servers = if (serverUrl.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    RoamingClient.RoamingServer(
                        area = json.optString("unlock_server_area", "cn"),
                        baseUrl = serverUrl,
                        accessKey = json.optString("unlock_server_access_key", ""),
                    ),
                )
            }
            Config(enabled, servers, testEpId, cacheUnlock, uposHost, passthrough)
        }.onFailure { t ->
            module.warn("unlock: config read failed: ${t.message}")
        }.getOrDefault(Config.DEFAULT)
    }
}
