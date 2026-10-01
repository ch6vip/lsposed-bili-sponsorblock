package com.ctf.bilisb.unlock

/**
 * CDN upos 域名替换（解锁 U5，纯 JVM 可单测）。
 *
 * 漫游服务器返回的流地址指向 B 站 CDN（upos 系：bilivideo.com / akamaized.net），
 * 部分运营商对其中一些 CDN 慢；替换 host 为镜像/加速节点是参考实现
 * UposReplaceHelper 的核心行为。PCDN 形态（mcdn./IP:port）无法简单替换 host，跳过。
 */
object UposReplacer {

    /** B 站 CDN 家族匹配：upos 系 bilivideo / akamaized。 */
    fun isUposUrl(url: String): Boolean {
        val host = hostOf(url)
        return host.contains("bilivideo.com") || host.contains("akamaized.net")
    }

    /** PCDN 形态（mcdn / IP:port / gotcha 直连）：host 不可简单替换。 */
    fun isPcdnUrl(url: String): Boolean {
        val host = hostOf(url)
        return host.startsWith("mcdn.") ||
            host.contains(".mcdn.bilivideo") ||
            host.contains("szbdyd.com") ||
            Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?$").matches(host)
    }

    /** 把 URL 的 host 替换为 [newHost]，路径与参数原样保留。 */
    fun replaceHost(url: String, newHost: String): String {
        val idx = url.indexOf("://")
        if (idx < 0) return url
        val rest = url.substring(idx + 3)
        val pathStart = rest.indexOf('/')
        if (pathStart < 0) return url
        return url.substring(0, idx + 3) + newHost + rest.substring(pathStart)
    }

    private fun hostOf(url: String): String {
        val idx = url.indexOf("://")
        if (idx < 0) return ""
        val rest = url.substring(idx + 3)
        return rest.substringBefore('/').substringBefore(':')
    }

    /**
     * 对漫游响应解析出的双轨应用 host 替换（非 PCDN 的 B 站 CDN URL）。
     * [newHost] 为空时不做任何替换。
     */
    fun applyTo(data: PlayurlData, newHost: String): PlayurlData {
        if (newHost.isBlank()) return data
        fun rewrite(t: DashTrack): DashTrack {
            if (!isUposUrl(t.baseUrl) || isPcdnUrl(t.baseUrl)) return t
            return t.copy(
                baseUrl = replaceHost(t.baseUrl, newHost),
                backupUrls = t.backupUrls.map { bk -> if (isUposUrl(bk) && !isPcdnUrl(bk)) replaceHost(bk, newHost) else bk },
            )
        }
        return data.copy(videos = data.videos.map(::rewrite), audios = data.audios.map(::rewrite))
    }
}
