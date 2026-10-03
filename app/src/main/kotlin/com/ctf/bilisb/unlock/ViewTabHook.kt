package com.ctf.bilisb.unlock

import android.os.Handler
import android.os.HandlerThread
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HostTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.PRIORITY_DEFAULT
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 选集面板解锁（ViewTab）：hook `app.viewunite.v1.ViewMoss.view/executeView` 响应，
 * 受限标题的 tab 缺正季选集区块时，经漫游服务端 `/pgc/view/web/season`（TW 出口，
 * 2026-10-04 实证：CN season API 的地区门按 IP 判定，出口视角全量可取且 CN/intl 共库
 * 编号）拉季数据，把 Module{type=13, section_data} 追加进 reply.tab 的简介页。
 *
 * 数据链实证（2026-10-04，輝夜姬 ss33088 vs 迷宫饭 ss47083 字节级对照）：
 *  - 选集卡片住在 `ViewReply.tab(5) → tab_module(1){tab_type=1} → introduction(2)
 *    → modules(2) → Module{type=1=13, section_data(12)=SectionData}`；
 *  - 受限时服务端仍下发完整 ogvData（seasonId/title/totalEp 都在）但 tab 里**没有
 *    正季 SectionData**（輝夜姬只剩 4 张 PV/特别篇卡）——页面因此不渲染选集，
 *    `pgc.gateway.view.v1.ViewMoss.seasonSections` 六方法全程 0 调用（两轮真机观测）；
 *  - `viewunite.common.ViewEpisode` 字段号与项目既有 SeasonParser/UnlockWire 完全对齐。
 *
 * 注入用 wire 级拼接（[WireSplice]）：reply→tab→tab_module→introduction→modules 逐层
 * 定点追加，未知字段零损；宿主类 parseFrom 重建。失败一律放行原响应。
 */
object ViewTabHook {

    private const val MAX_RETRY = 8
    private const val RETRY_DELAY_MS = 4000L

    /** viewunite 的 PGC 载荷 typeUrl（classes18.dex 字符串池实证）。 */
    const val VIEW_PGC_ANY_TYPE_URL =
        "type.googleapis.com/bilibili.app.viewunite.pgcanymodel.ViewPgcAny"

    /** Module.type=13 = 选集 SectionData 区块（迷宫饭 ss47083 实拍）。 */
    private const val MODULE_TYPE_SECTION_DATA = 13L

    private val attempts = AtomicInteger(0)
    private val captured = AtomicInteger(0)

    /** season_id → 剧集（进程内缓存，避免每次进详情页都打服务端）。 */
    private val episodesBySeasonId = ConcurrentHashMap<Int, List<SeasonEpisode>>()

    private val executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "BiliSB-ViewTabNet").apply { isDaemon = true }
        }
    }

    private val retryHandler: Handler by lazy {
        val thread = HandlerThread("BiliSB-ViewTabRetry").apply { isDaemon = true }
        thread.start()
        Handler(thread.looper)
    }

    fun install(module: XposedModule, cl: ClassLoader) {
        tryInstall(module, cl)
    }

    private fun tryInstall(module: XposedModule, cl: ClassLoader) {
        if (attempts.incrementAndGet() > MAX_RETRY) {
            HookProbe.miss(module, "viewTab", "give up after $MAX_RETRY attempts")
            return
        }
        try {
            val moss = Class.forName(HostTargets.VIEW_UNITE_MOSS_CLASS, false, cl)
            val hooked = mutableListOf<String>()
            for (m in moss.declaredMethods) {
                val isTwoArg = m.name == "view" && m.parameterTypes.size == 2
                val isOneArg = m.name == "executeView" && m.parameterTypes.size == 1
                if (!isTwoArg && !isOneArg) continue
                runCatching { m.isAccessible = true }
                runCatching { module.deoptimize(m) }
                hookOne(module, cl, m, wrapHandler = isTwoArg)
                hooked += "${m.name}(${m.parameterTypes.size})"
            }
            if (hooked.isEmpty()) {
                HookProbe.first(module, "viewTabRetry", 3) {
                    "view/executeView not found yet, attempt=${attempts.get()}"
                }
                retryHandler.postDelayed({ tryInstall(module, cl) }, RETRY_DELAY_MS)
                return
            }
            HookProbe.ok(module, "viewTab", hooked.joinToString(", "))
        } catch (e: ClassNotFoundException) {
            HookProbe.first(module, "viewTabRetry", 3) {
                "class not loaded yet, attempt=${attempts.get()}"
            }
            retryHandler.postDelayed({ tryInstall(module, cl) }, RETRY_DELAY_MS)
        } catch (t: Throwable) {
            HookProbe.miss(module, "viewTab", "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun hookOne(module: XposedModule, cl: ClassLoader, m: Method, wrapHandler: Boolean) {
        module.hook(m)
            .setPriority(PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val req = chain.args.getOrNull(0)
                if (wrapHandler && chain.args.size >= 2 && chain.args[1] != null) {
                    val handler = chain.args[1]
                    val wrapped = Proxy.newProxyInstance(
                        cl,
                        handler.javaClass.interfaces,
                        { _, method, args ->
                            if (args != null && args.isNotEmpty() && isCarrier(method.name)) {
                                args[0] = transform(module, cl, req, args[0])
                            }
                            method.invoke(handler, *(args ?: emptyArray()))
                        },
                    )
                    return@intercept chain.proceed(arrayOf(req, wrapped))
                }
                transform(module, cl, req, chain.proceed())
            }
    }

    private fun isCarrier(name: String): Boolean =
        name == "onNext" || name == "onCompleted" || name == "resumeWith" || name == "onSuccess"

    // ---- 变换主链 ----

    private fun transform(module: XposedModule, cl: ClassLoader, req: Any?, reply: Any?): Any? {
        if (reply == null) return null
        return runCatching {
            val config = UnlockConfig.load(module)
            if (!config.enabled || config.servers.isEmpty()) return@runCatching reply
            val replyBytes = reply.javaClass.methods.firstOrNull { f -> f.name == "toByteArray" }
                ?.invoke(reply) as? ByteArray ?: return@runCatching reply
            captureOnce(module, reply, "pre")

            // supplement 须为 ViewPgcAny 且 ogvData 带 seasonId
            val ogvData = extractOgvData(replyBytes) ?: return@runCatching reply
            val seasonId = WireSplice.parse(ogvData)
                .firstOrNull { it.field == 2 && it.wireType == 0 }?.varint()?.toInt() ?: 0
            if (seasonId == 0) {
                HookProbe.first(module, "viewTab:skipNoSeason", 3) { "ogvData 缺 seasonId" }
                return@runCatching reply
            }

            val tabBytes = WireSplice.firstMessage(replyBytes, 5)
            if (tabBytes == null || panelPresent(tabBytes)) {
                HookProbe.first(module, "viewTab:skipHasPanel", 3) { "sid=$seasonId tab 已有选集区块" }
                return@runCatching reply
            }

            val serverBase = config.servers.first().baseUrl.trimEnd('/')
            val accessKey = config.servers.first().accessKey.ifBlank { HostAccessKey.lastSeen() } ?: ""
            val episodes = episodesBySeasonId.getOrPut(seasonId) {
                val task = java.util.concurrent.Callable {
                    val url = "$serverBase/pgc/view/web/season?season_id=$seasonId" +
                        (if (accessKey.isNotEmpty()) "&access_key=$accessKey" else "") +
                        "&build=9070300&mobi_app=android&platform=android"
                    val body = PlayViewHook.defaultFetch(url, "android")
                    SeasonParser.parseFlatEpisodes(body)
                }
                executor.submit(task).get(10, TimeUnit.SECONDS)
            }
            if (episodes.isEmpty()) {
                HookProbe.first(module, "viewTab:seasonFail", 3) { "sid=$seasonId 服务端季数据为空" }
                return@runCatching reply
            }

            val moduleBytes = buildSectionModuleBytes(seasonId, episodes)
            val newTab = appendSectionModule(tabBytes, moduleBytes)
            val newReplyBytes = WireSplice.transformMessage(replyBytes, 5) { newTab }
            val rebuilt = cl.loadClass(HostTargets.VIEW_UNITE_REPLY_CLASS)
                .getMethod("parseFrom", ByteArray::class.java)
                .invoke(null, newReplyBytes)
            HookProbe.first(module, "viewTab:injected", 3) {
                "sid=$seasonId eps=${episodes.size} (${replyBytes.size}B -> ${newReplyBytes.size}B)"
            }
            captureOnce(module, reply, "sid=$seasonId")
            rebuilt
        }.onFailure { t ->
            HookProbe.first(module, "viewTab:failed", 5) {
                var root = t
                while (root.cause != null) root = root.cause!!
                "${t.javaClass.simpleName}: ${t.message} <- ${root.javaClass.simpleName}: ${root.message}"
            }
        }.getOrNull() ?: reply
    }

    /** supplement(6) → Any.value → ViewPgcAny.ogvData(1)；形态不符返回 null。 */
    private fun extractOgvData(replyBytes: ByteArray): ByteArray? {
        val supplement = WireSplice.firstMessage(replyBytes, 6) ?: return null
        val sf = WireSplice.parse(supplement)
        val typeUrl = sf.firstOrNull { it.field == 1 && it.wireType == 2 }?.payload()
            ?.toString(Charsets.UTF_8)
        if (typeUrl != VIEW_PGC_ANY_TYPE_URL) return null
        val value = sf.firstOrNull { it.field == 2 && it.wireType == 2 }?.payload() ?: return null
        return WireSplice.firstMessage(value, 1)
    }

    /** tab 的简介页里是否已有选集 SectionData 区块（Module.type=13）。 */
    private fun panelPresent(tabBytes: ByteArray): Boolean {
        for (tm in WireSplice.allMessages(tabBytes, 1)) {
            val tabType = WireSplice.parse(tm).firstOrNull()?.takeIf { it.field == 1 && it.wireType == 0 }?.varint()
            if (tabType != 1L) continue
            val intro = WireSplice.firstMessage(tm, 2) ?: continue
            if (WireSplice.hasMessageWithFirstVarint(intro, 2, MODULE_TYPE_SECTION_DATA)) return true
        }
        return false
    }

    /**
     * Module{type=1:13, section_data(12)}：SectionData{id=1, section_id=2, title=3,
     * module_style(9)={1:1}, episodes(7)}——层级与取值照迷宫饭 ss47083 实拍模板。
     */
    private fun buildSectionModuleBytes(seasonId: Int, episodes: List<SeasonEpisode>): ByteArray {
        val sd = WireWriter()
        sd.int32Field(1, seasonId)
        sd.int32Field(2, seasonId)
        sd.stringField(3, "选集")
        sd.messageField(9, byteArrayOf(0x08, 0x01)) // Style{1:1}
        for (ep in episodes) sd.messageField(7, UnlockWire.buildViewEpisodeBytes(ep))
        val m = WireWriter()
        m.int32Field(1, 13)
        m.messageField(12, sd.toByteArray())
        return m.toByteArray()
    }

    /**
     * 把 Module 追加进 tab：tab_module{tab_type=1} 的 introduction.modules 尾部；
     * 其余 tab 字节零损保留。
     */
    private fun appendSectionModule(tabBytes: ByteArray, moduleBytes: ByteArray): ByteArray {
        val newTms = mutableListOf<ByteArray>()
        var touched = false
        for (tm in WireSplice.allMessages(tabBytes, 1)) {
            val tabType = WireSplice.parse(tm).firstOrNull()?.takeIf { it.field == 1 && it.wireType == 0 }?.varint()
            if (tabType == 1L && !touched) {
                touched = true
                val intro = WireSplice.firstMessage(tm, 2)
                val newIntro = if (intro != null) {
                    WireSplice.emit(WireSplice.parse(intro) + WireSplice.Elem(2, 2, WireSplice.message(2, moduleBytes)))
                } else {
                    WireSplice.message(2, moduleBytes)
                }
                newTms += WireSplice.transformMessage(tm, 2) { newIntro }
            } else {
                newTms += tm
            }
        }
        if (!touched) return tabBytes
        return WireSplice.emit(
            WireSplice.parse(tabBytes).map { e ->
                if (e.field == 1 && e.wireType == 2 && newTms.isNotEmpty()) {
                    WireSplice.Elem(1, 2, WireSplice.message(1, newTms.removeAt(0)))
                } else {
                    e
                }
            },
        )
    }

    private fun captureOnce(module: XposedModule, reply: Any, tag: String) {
        val n = captured.incrementAndGet()
        if (n > 6) return
        runCatching {
            val bytes = reply.javaClass.methods.firstOrNull { f -> f.name == "toByteArray" }
                ?.invoke(reply) as? ByteArray ?: return
            val dir = java.io.File(HostTargets.HOST_DATA_DIRS.first(), "unlock_capture")
            dir.mkdirs()
            val f = java.io.File(dir, "viewtab_reply_$n.bin")
            f.outputStream().use { it.write(bytes) }
            HookProbe.first(module, "viewTab:capture", 6) { "${f.absolutePath} ${bytes.size}B $tag" }
        }
    }
}
