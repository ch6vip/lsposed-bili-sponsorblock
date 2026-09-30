package com.ctf.bilisb.unlock

import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HostTargets
import io.github.libxposed.api.XposedModule

/**
 * 借宿主的请求签名（docs/UNLOCK_FEASIBILITY.md §2.3）：
 *
 * `com.bilibili.nativelibrary.LibBili` 的静态方法 `(Map<String,String>) -> SignedQuery`
 * （方法名按签名形状解析，混淆漂移时形状匹配兜底），`SignedQuery.toString()` 即
 * 已签名的查询串。调用失败退化为恒等签名——mock 服务器不验签不受影响；
 * 真实服务器会对未签名请求拒绝（探针留名，不会静默）。
 */
class HostSigner(private val module: XposedModule, private val cl: ClassLoader) :
    (String, Map<String, String>) -> String {

    private val signMethod by lazy {
        runCatching {
            val libBili = cl.loadClass(HostTargets.LIB_BILI_CLASS)
            val signedQueryCls = cl.loadClass(HostTargets.SIGNED_QUERY_CLASS)
            libBili.declaredMethods.firstOrNull {
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Map::class.java &&
                    it.returnType == signedQueryCls
            }?.apply { isAccessible = true }
        }.getOrNull()
    }

    override fun invoke(query: String, extra: Map<String, String>): String {
        val method = signMethod ?: run {
            HookProbe.first(module, "unlock:signFallback", 3) { "宿主签名方法不可用，恒等签名（mock 可用，真实服务器将拒绝）" }
            return merge(query, extra)
        }
        return runCatching {
            val map = buildMap {
                for (pair in query.split('&')) {
                    val idx = pair.indexOf('=')
                    if (idx > 0) put(pair.take(idx), pair.substring(idx + 1))
                }
                putAll(extra)
            }
            val signed = method.invoke(null, map)
            signed.toString()
        }.onFailure { t ->
            HookProbe.first(module, "unlock:signFallback", 3) {
                "签名调用失败: ${t.javaClass.simpleName}: ${t.message}"
            }
        }.getOrDefault(merge(query, extra))
    }

    private fun merge(query: String, extra: Map<String, String>): String =
        query + extra.entries.joinToString("") { "&${it.key}=${it.value}" }
}
