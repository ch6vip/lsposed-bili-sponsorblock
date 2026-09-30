package com.ctf.bilisb.unlock

import android.os.Handler
import android.os.HandlerThread
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * `PlayerMoss.playViewUnite` 的**只读观测钩**（解锁功能 U1，见 docs/UNLOCK_PLAN.md）。
 *
 * 本钩**不改任何行为**：拦截后先提取请求事实（vod.cid / extraContent 的 season_id、ep_id、
 * download 标记），proceed 后提取响应事实（playArc.cid / supplement typeUrl / 结果是否存在），
 * 交给 [PlayViewDecision] 判定并打限频探针。受限/重定向的**处置**（走漫游服务器）是 U4 的事，
 * 这里只负责把「受限判定」从推测变成日志实证。
 *
 * 安装时 `PlayerMoss` 往往还没加载（播放器类按需加载），照 IP 属地的延迟重试模式
 * （30 次 × 1s）；give-up 探针留名——若真机显示 give-up 率高（冷启动后久不开播放页），
 * 改挂到播放器 bind 事件后再装（UNLOCK_PLAN U4 备选）。
 */
object PlayViewHook {

    private const val MAX_RETRY = 30
    private const val RETRY_DELAY_MS = 1000L

    private val attempts = AtomicInteger(0)

    /** 延迟重试用的自建后台线程：重试做类加载 + 方法扫描，不压主线程。 */
    private val retryHandler: Handler by lazy {
        val thread = HandlerThread("BiliSB-UnlockRetry")
        thread.isDaemon = true
        thread.start()
        Handler(thread.looper)
    }

    fun install(module: XposedModule, cl: ClassLoader) {
        tryInstall(module, cl)
    }

    private fun tryInstall(module: XposedModule, cl: ClassLoader) {
        if (attempts.incrementAndGet() > MAX_RETRY) {
            HookProbe.miss(module, "unlock:playViewUnite", "give up after $MAX_RETRY attempts")
            return
        }
        try {
            val moss = Class.forName(HostTargets.PLAYER_MOSS_CLASS, false, cl)
            val hooked = mutableListOf<String>()
            for (name in HostTargets.PLAY_VIEW_UNITE_METHODS) {
                for (m in moss.declaredMethods.filter {
                    it.name == name && it.parameterTypes.isNotEmpty()
                }) {
                    runCatching { m.isAccessible = true }
                    runCatching { module.deoptimize(m) }
                    hookOne(module, m)
                    hooked += "${m.name}(${m.parameterTypes.size} args)"
                }
            }
            if (hooked.isEmpty()) {
                HookProbe.first(module, "unlock:playViewUniteRetry", 3) {
                    "method not found yet, retry attempt=${attempts.get()}"
                }
                retry(module, cl)
                return
            }
            HookProbe.ok(module, "unlock:playViewUnite", hooked.joinToString(", "))
        } catch (e: ClassNotFoundException) {
            HookProbe.first(module, "unlock:playViewUniteRetry", 3) {
                "class not loaded yet, retry in ${RETRY_DELAY_MS}ms attempt=${attempts.get()}"
            }
            retry(module, cl)
        } catch (t: Throwable) {
            HookProbe.miss(module, "unlock:playViewUnite", "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun retry(module: XposedModule, cl: ClassLoader) {
        runCatching {
            retryHandler.postDelayed({ tryInstall(module, cl) }, RETRY_DELAY_MS)
        }.onFailure { t -> module.warn("unlock: retry scheduling failed: ${t.message}") }
    }

    /** 单方法单钩：before 提取请求事实，proceed 后提取响应事实并判定。异常一律退化为放行。 */
    private fun hookOne(module: XposedModule, m: Method) {
        module.hook(m)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val reqFacts = extractRequestFacts(module, chain.args.getOrNull(0))
                HookProbe.first(module, "unlock:reqFacts", 5) {
                    "${m.name}: cid=${reqFacts.vodCid} season=${reqFacts.seasonId} " +
                        "ep=${reqFacts.epId} download=${reqFacts.isDownload}"
                }
                val result = chain.proceed()
                val respFacts = extractResponseFacts(module, result)
                val facts = PlayViewDecision.Facts(
                    reqVodCid = reqFacts.vodCid,
                    seasonId = reqFacts.seasonId,
                    epId = reqFacts.epId,
                    isDownload = reqFacts.isDownload,
                    respUsable = respFacts.usable,
                    respPlayArcCid = respFacts.respCid,
                    supplementTypeUrl = respFacts.typeUrl,
                )
                val verdict = PlayViewDecision.classify(facts)
                HookProbe.first(module, "unlock:verdict:${verdict.name}", 5) {
                    "cid=${facts.reqVodCid} respCid=${facts.respPlayArcCid} " +
                        "typeUrl=${facts.supplementTypeUrl ?: "null"} usable=${facts.respUsable}"
                }
                result
            }
    }

    private data class RequestFacts(val vodCid: Long, val seasonId: String, val epId: String, val isDownload: Boolean)

    private data class ResponseFacts(val usable: Boolean, val respCid: Long, val typeUrl: String?)

    /** getVod().getCid()/getDownload() + getExtraContentMap()；任何失败按缺省值降级并留探针。 */
    private fun extractRequestFacts(module: XposedModule, req: Any?): RequestFacts {
        if (req == null) return RequestFacts(0, "0", "0", isDownload = false)
        var vodCid = 0L
        var seasonId = "0"
        var epId = "0"
        var isDownload = false
        runCatching {
            req.javaClass.methods.firstOrNull { it.name == "getVod" }?.invoke(req)?.let { vod ->
                vodCid = (vod.javaClass.methods.firstOrNull { it.name == "getCid" }?.invoke(vod) as? Number)?.toLong() ?: 0L
                isDownload = ((vod.javaClass.methods.firstOrNull { it.name == "getDownload" }?.invoke(vod) as? Number)?.toInt() ?: 0) >= 1
            }
            val extra = req.javaClass.methods.firstOrNull { it.name == "getExtraContentMap" }?.invoke(req)
            @Suppress("UNCHECKED_CAST")
            (extra as? Map<String, String>)?.let { map ->
                seasonId = map.getOrDefault("season_id", "0")
                epId = map.getOrDefault("ep_id", "0")
            }
        }.onFailure { t ->
            HookProbe.first(module, "unlock:extractFailed", 3) { "req: ${t.javaClass.simpleName}: ${t.message}" }
        }
        return RequestFacts(vodCid, seasonId, epId, isDownload)
    }

    /** hasVodInfo / getPlayArc().getCid() / getSupplement().getTypeUrl()；失败按缺省值降级并留探针。 */
    private fun extractResponseFacts(module: XposedModule, result: Any?): ResponseFacts {
        var usable = result != null
        var respCid = 0L
        var typeUrl: String? = null
        runCatching {
            result?.let { res ->
                val hasVod = res.javaClass.methods.firstOrNull { it.name == "hasVodInfo" }
                    ?.invoke(res) as? Boolean
                if (hasVod == false) return ResponseFacts(false, 0L, null)
                val playArc = res.javaClass.methods.firstOrNull { it.name == "getPlayArc" }?.invoke(res)
                playArc?.let {
                    respCid = (it.javaClass.methods.firstOrNull { f -> f.name == "getCid" }?.invoke(it) as? Number)?.toLong() ?: 0L
                }
                val supplement = res.javaClass.methods.firstOrNull { it.name == "getSupplement" }?.invoke(res)
                typeUrl = supplement?.javaClass?.methods?.firstOrNull { f -> f.name == "getTypeUrl" }?.invoke(supplement) as? String
            }
        }.onFailure { t ->
            HookProbe.first(module, "unlock:extractFailed", 3) { "resp: ${t.javaClass.simpleName}: ${t.message}" }
        }
        return ResponseFacts(usable, respCid, typeUrl)
    }
}
