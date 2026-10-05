package com.ctf.bilisb.unlock

/** 区域页用独立 type；原生番剧/影视请求不受解锁设置影响。 */
internal object SearchRequestPolicy {
    const val MARKER_TYPE = 810
    const val ROUTE_MARKER = "bilisb_unlock"
    enum class Route { ORIGINAL, REGIONAL, NATIVE_BANGUMI }

    fun route(type: Int, enabled: Boolean, searchEnabled: Boolean, hasServer: Boolean, area: String): Route {
        if (type != MARKER_TYPE) return Route.ORIGINAL
        return if (enabled && searchEnabled && hasServer && area in setOf("hk", "tw", "th")) {
            Route.REGIONAL
        } else Route.NATIVE_BANGUMI // 已打开的区域页关闭开关后仍能回到原生搜索。
    }

    data class Page(val number: Int, val size: Int)

    fun page(next: String?, size: Int): Page {
        val number = if (next.isNullOrBlank()) 1 else {
            requireNotNull(next.toIntOrNull()?.takeIf { it > 0 && it < Int.MAX_VALUE }) { "Invalid search cursor" }
        }
        return Page(number, if (size > 0) size.coerceAtMost(100) else 20)
    }
}
