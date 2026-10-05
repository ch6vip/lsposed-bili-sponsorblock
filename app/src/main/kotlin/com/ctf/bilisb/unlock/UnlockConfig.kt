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
        /** 搜索解锁（S 线）：searchAll 注入区域页签 + searchByType 标记页签短路。 */
        val searchEnabled: Boolean,
        /** 解锁运行状态信息提示（show_info，默认 true）。 */
        val unlockShowInfo: Boolean = true,
        /** 全屏清晰度策略（0=默认，-1=自动最高，其余为 qn 代码）。 */
        val fullScreenQuality: String = "0",
        /** 半屏清晰度策略（0=默认，1=跟随全屏，-1=自动最高，其余为 qn 代码）。 */
        val halfScreenQuality: String = "0",
        /** UPOS 应用到所有视频与阻止 PCDN（force_upos）。 */
        val forceUpos: Boolean = false,
        /** 四区独立解析服务器地址。 */
        val serverCn: String = "",
        val serverHk: String = "",
        val serverTw: String = "",
        val serverTh: String = "",
        /** 根据繁体字幕自动生成简体中文字幕。 */
        val autoGenerateSubtitle: Boolean = false,
        /** 泰区/东南亚多语言字幕注入。 */
        val thSubtitle: Boolean = true,
        /** 添加其他地区番剧（在首页顶栏导航添加大陆与港澳台追番分页）。 */
        val addBangumi: Boolean = false,
    ) {
        companion object {
            val DEFAULT = Config(
                enabled = false,
                servers = emptyList(),
                testEpId = 0,
                cacheUnlock = false,
                uposHost = "",
                passthrough = false,
                searchEnabled = false,
                unlockShowInfo = true,
                fullScreenQuality = "0",
                halfScreenQuality = "0",
                forceUpos = false,
                serverCn = "",
                serverHk = "",
                serverTw = "",
                serverTh = "",
                autoGenerateSubtitle = false,
                thSubtitle = true,
                addBangumi = false,
            )
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
            val searchEnabled = json.optBoolean("unlock_search", false)
            val passthrough = json.optBoolean("unlock_passthrough", false)
            val unlockShowInfo = json.optBoolean("unlock_show_info", true)
            val fullScreenQuality = json.optString("full_screen_quality", "0")
            val halfScreenQuality = json.optString("half_screen_quality", "0")
            val forceUpos = json.optBoolean("unlock_force_upos", false)
            val serverCn = json.optString("unlock_server_cn", "").trim()
            val serverHk = json.optString("unlock_server_hk", "").trim()
            val serverTw = json.optString("unlock_server_tw", "").trim()
            val serverTh = json.optString("unlock_server_th", "").trim()
            val autoGenerateSubtitle = json.optBoolean("unlock_auto_generate_subtitle", false)
            val thSubtitle = json.optBoolean("unlock_th_subtitle", true)
            val addBangumi = json.optBoolean("unlock_add_bangumi", false)
            val ak = json.optString("unlock_server_access_key", "")

            val serverList = mutableListOf<RoamingClient.RoamingServer>()
            if (serverCn.isNotEmpty()) serverList.add(RoamingClient.RoamingServer("cn", serverCn, ak))
            if (serverHk.isNotEmpty()) serverList.add(RoamingClient.RoamingServer("hk", serverHk, ak))
            if (serverTw.isNotEmpty()) serverList.add(RoamingClient.RoamingServer("tw", serverTw, ak))
            if (serverTh.isNotEmpty()) serverList.add(RoamingClient.RoamingServer("th", serverTh, ak))

            val serverUrl = json.optString("unlock_server_url", "").trim()
            if (serverUrl.isNotEmpty()) {
                val area = json.optString("unlock_server_area", "cn").ifBlank { "cn" }
                if (serverList.none { it.area == area }) {
                    serverList.add(RoamingClient.RoamingServer(area = area, baseUrl = serverUrl, accessKey = ak))
                }
            }

            Config(
                enabled = enabled,
                servers = serverList,
                testEpId = testEpId,
                cacheUnlock = cacheUnlock,
                uposHost = uposHost,
                passthrough = passthrough,
                searchEnabled = searchEnabled,
                unlockShowInfo = unlockShowInfo,
                fullScreenQuality = fullScreenQuality,
                halfScreenQuality = halfScreenQuality,
                forceUpos = forceUpos,
                serverCn = serverCn,
                serverHk = serverHk,
                serverTw = serverTw,
                serverTh = serverTh,
                autoGenerateSubtitle = autoGenerateSubtitle,
                thSubtitle = thSubtitle,
                addBangumi = addBangumi,
            ).also {
                module.warn(
                    "unlock:cfgLoaded file=${file.path} len=${file.length()} " +
                        "enabled=${it.enabled} servers=${it.servers.size} " +
                        "showInfo=${it.unlockShowInfo} forceUpos=${it.forceUpos} " +
                        "fsQuality=${it.fullScreenQuality} hsQuality=${it.halfScreenQuality}",
                )
            }
        }.onFailure { t ->
            module.warn("unlock: config read failed: ${t.message}")
        }.getOrDefault(Config.DEFAULT)
    }
}
