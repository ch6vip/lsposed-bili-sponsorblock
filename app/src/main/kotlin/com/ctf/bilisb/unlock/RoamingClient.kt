package com.ctf.bilisb.unlock

/**
 * 漫游服务器协议客户端（解锁 U3，见 docs/UNLOCK_PLAN.md）。
 *
 * 协议事实（参考实现研读 + Phase 0 实证）：
 *  - 请求：`GET {server}{path}?{query}`，path 按区域区分——
 *    `th`（东南亚）走 `/intl/gateway/v2/ogv/playurl` 且 extra 多 appkey/build/mobi_app/platform，
 *    其余（tw/hk/cn）走 `/pgc/player/api/playurl` 且 extra 只有 area + access_key。
 *  - 签名：查询串由**宿主的 appkey/secret 签名静态方法**签名（借宿主，不自己实现）；
 *    本类把签名作为注入函数，生产实现接宿主反射（U4），单测用 fake。
 *  - 探活：响应含 `"code":0` 即成功；逐台降级，全失败把各台错误带回。
 *  - 服务器（含 accessKey）由用户按区配置，模块不含内置服务器。
 *
 * 纯 JVM：签名与 HTTP 传输全部注入，单测不触网。
 */
class RoamingClient(
    /** 借宿主的请求签名：输入查询串与附加参数，输出已签名查询串。生产实现接宿主反射。 */
    private val sign: (query: String, extra: Map<String, String>) -> String,
    /** HTTP 传输：GET 并返回响应体（含 gzip/超时处理）。失败抛异常。 */
    private val fetch: (url: String, mobiApp: String) -> String,
    /** 请求头/参数用的 mobi_app 标识。 */
    private val mobiApp: String = "android",
) {

    data class RoamingServer(val area: String, val baseUrl: String, val accessKey: String)

    /** 播放地址查询参数（reconstructQueryUnite 的七参数集）。 */
    data class PlayQuery(
        val epId: Long,
        val cid: Long,
        val qn: Long,
        val fnver: Int,
        val fnval: Int,
        val forceHost: Int,
        val fourk: Boolean,
    )

    data class RoamingResult(
        /** 成功时为响应体（经典 playurl JSON）；全失败为 null。 */
        val content: String?,
        val areaUsed: String?,
        /** 每台失败服务器的错误摘要（探针与错误呈现用）。 */
        val errors: Map<String, String>,
    ) {
        val isSuccess: Boolean get() = content != null
    }

    /**
     * 逐台尝试 [servers]（按给定优先级顺序），返回第一台响应 `"code":0` 的结果。
     *
     * [priorityArea] 指定时把该区提到最前（U4 供「上次成功区域缓存」用）。
     */
    fun fetchPlayUrl(
        servers: List<RoamingServer>,
        query: PlayQuery,
        priorityArea: String? = null,
    ): RoamingResult {
        val ordered = if (priorityArea != null) {
            servers.sortedByDescending { it.area == priorityArea }
        } else {
            servers
        }
        val queryString = buildQuery(query)
        val errors = LinkedHashMap<String, String>()
        for (server in ordered) {
            val thailand = server.area == "th"
            val extra = if (thailand) {
                mapOf(
                    "area" to server.area,
                    "appkey" to TH_APPKEY,
                    "build" to TH_BUILD,
                    "mobi_app" to TH_MOBI_APP,
                    "platform" to "android",
                    "access_key" to server.accessKey,
                )
            } else {
                mapOf(
                    "area" to server.area,
                    "access_key" to server.accessKey,
                )
            }
            val path = if (thailand) PATH_THAILAND_PLAYURL else PATH_PLAYURL
            try {
                val url = server.baseUrl.trimEnd('/') + path + "?" + sign(queryString, extra)
                val content = fetch(url, if (thailand) TH_MOBI_APP else mobiApp)
                if (isCodeZero(content)) {
                    return RoamingResult(content, server.area, errors)
                }
                errors[server.area] = "code != 0: ${content.take(120)}"
            } catch (t: Throwable) {
                errors[server.area] = "${t.javaClass.simpleName}: ${t.message}"
            }
        }
        return RoamingResult(null, null, errors)
    }

    companion object {
        const val PATH_PLAYURL = "/pgc/player/api/playurl"
        const val PATH_THAILAND_PLAYURL = "/intl/gateway/v2/ogv/playurl"

        /** 东南亚通道的固定参数（与 B 站国际 API 客户端身份一致）。 */
        const val TH_APPKEY = "7d089525d3611b1c"
        const val TH_BUILD = "1001310"
        const val TH_MOBI_APP = "bstar_a"

        /** 探活：code==0（JSON 解析，兼容 kghost 的外层 result 包装与格式空格差异）。 */
        fun isCodeZero(content: String): Boolean = runCatching {
            var json = org.json.JSONObject(content)
            json.opt("result")?.let { r -> if (r !is String) json = json.getJSONObject("result") }
            json.optInt("code", -1) == 0
        }.getOrDefault(false)

        /** 七参数查询串：参数为数字，无转义需求；顺序与参考实现一致。 */
        fun buildQuery(q: PlayQuery): String =
            "ep_id=${q.epId}" +
                "&cid=${q.cid}" +
                "&qn=${q.qn}" +
                "&fnver=${q.fnver}" +
                "&fnval=${q.fnval}" +
                "&force_host=${q.forceHost}" +
                "&fourk=${if (q.fourk) "1" else "0"}"
    }
}
