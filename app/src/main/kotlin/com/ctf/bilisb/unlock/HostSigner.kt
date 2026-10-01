package com.ctf.bilisb.unlock

import java.security.MessageDigest

/**
 * 漫游请求签名（U4.7 最终形态）：**按区域本地签名**——各端 appkey/secret 是公开常量
 * （B 站客户端密钥，服务器源码 ClientType 表同源），无需借宿主。
 *
 * 区域 → 身份映射（与 BiliRoaming 参考实现一致）：
 *  - th（东南亚）→ BstarA：7d089525d3611b1c / acd495b248ec528c2eed1e862d393126
 *    （国际令牌的正确归宿——intl 网关认国际令牌）
 *  - cn/hk/tw → Android：1d8b6e7d45233436 / 560c52ccd288fed045859ed18bffd973
 *    （国内令牌的归宿——主站 API 认国内令牌）
 *
 * 签名算法：参数按 key 排序拼接 + secret，md5 十六进制（B 站 appsign 标准算法）。
 * 早期版本借宿主 LibBili 签名——但宿主只认自己体系的 appkey，跨区域请求会得到
 * 上游 -3"API校验密匙错误"（2026-10-02 真机实测）。
 */
object HostSigner {

    private const val ANDROID_KEY = "1d8b6e7d45233436"
    private const val ANDROID_SEC = "560c52ccd288fed045859ed18bffd973"
    private const val BSTARA_KEY = "7d089525d3611b1c"
    private const val BSTARA_SEC = "acd495b248ec528c2eed1e862d393126"

    fun sign(area: String, query: String, extra: Map<String, String>): String {
        val map = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx > 0) map[pair.take(idx)] = pair.substring(idx + 1)
        }
        map.putAll(extra)
        val (appkey, secret) = when (area) {
            "th" -> BSTARA_KEY to BSTARA_SEC
            else -> ANDROID_KEY to ANDROID_SEC
        }
        map["appkey"] = appkey
        val sorted = map.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        val sign = MessageDigest.getInstance("MD5")
            .digest((sorted + secret).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$sorted&sign=$sign"
    }
}
