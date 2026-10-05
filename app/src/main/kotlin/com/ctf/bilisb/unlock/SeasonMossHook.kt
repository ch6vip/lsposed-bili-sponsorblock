package com.ctf.bilisb.unlock

import android.os.Handler
import android.os.HandlerThread
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.PRIORITY_DEFAULT
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 选集面板解锁（SeasonMoss）：hook `ViewMoss.seasonSections/pageSectionEpisodes`
 * 的 moss 响应——区域受限时（reply 无 sections/episodes）拉
 * `api.bilibili.com/pgc/view/web/season?season_id=`（CN 直连匿名可取，字段号
 * 静态实证见 bilisb_unlock.proto）重建两响应注入，选集面板得以渲染。
 *
 * hook 形态与 [PlayViewHook] 完全同构：双参 (req, handler) 包回调代理，
 * 单参挂起形态变换返回值；载体方法名判定复用 onNext/resumeWith 一组。
 */
object SeasonMossHook {

    private const val TAG = "SeasonMossHook"
    private const val MAX_RETRY = 8
    private const val RETRY_DELAY_MS = 4000L

    /** section_id → 分区数据（seasonSections 注入时缓存，pageSectionEpisodes 复用）。 */
    private val sectionsBySectionId = ConcurrentHashMap<Int, SeasonSection>()

    /** aid → season_id（请求只带 aid 时的回源键）。 */
    private val seasonIdByAid = ConcurrentHashMap<Long, Int>()

    private val attempts = AtomicInteger(0)

    /** 网络/构建的专用单线程（seasonSections 回调在主线程，网络必须挪走）。 */
    private val executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "BiliSB-SeasonNet").apply { isDaemon = true }
        }
    }

    private val retryHandler: Handler by lazy {
        val thread = HandlerThread("BiliSB-SeasonRetry").apply { isDaemon = true }
        thread.start()
        Handler(thread.looper)
    }

    fun install(module: XposedModule, cl: ClassLoader) {
        tryInstall(module, cl)
    }

    private fun tryInstall(module: XposedModule, cl: ClassLoader) {
        if (attempts.incrementAndGet() > MAX_RETRY) {
            HookProbe.miss(module, "seasonMoss", "give up after $MAX_RETRY attempts")
            return
        }
        try {
            val moss = Class.forName(HostTargets.VIEW_MOSS_CLASS, false, cl)
            val hooked = mutableListOf<String>()
            for (name in HostTargets.SEASON_MOSS_METHODS) {
                for (m in moss.declaredMethods.filter {
                    it.name == name && it.parameterTypes.isNotEmpty()
                }) {
                    runCatching { m.isAccessible = true }
                    runCatching { module.deoptimize(m) }
                    hookOne(module, cl, m)
                    hooked += "${m.name}(${m.parameterTypes.size})"
                }
            }
            if (hooked.isEmpty()) {
                HookProbe.first(module, "seasonMossRetry", 3) {
                    "method not found yet, attempt=${attempts.get()}"
                }
                retryHandler.postDelayed({ tryInstall(module, cl) }, RETRY_DELAY_MS)
                return
            }
            HookProbe.ok(module, "seasonMoss", hooked.joinToString(", "))
        } catch (e: ClassNotFoundException) {
            HookProbe.first(module, "seasonMossRetry", 3) {
                "class not loaded yet, attempt=${attempts.get()}"
            }
            retryHandler.postDelayed({ tryInstall(module, cl) }, RETRY_DELAY_MS)
        } catch (t: Throwable) {
            HookProbe.miss(module, "seasonMoss", "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun hookOne(module: XposedModule, cl: ClassLoader, m: Method) {
        module.hook(m)
            .setPriority(PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val req = chain.args.getOrNull(0)
                // 探针在配置门之前:任何 season 调用(无论开关状态)都可见,
                // 用于区分「App 未发起调用」与「注入链路失败」
                HookProbe.first(module, "seasonMoss:req", 3) {
                    "method=${m.name} args=${chain.args.size}"
                }
                val config = UnlockConfig.load(module)
                if (!config.enabled || config.servers.isEmpty() || req == null) {
                    return@intercept chain.proceed()
                }
                val injector = ReplyInjector(module, cl, req)

                // 双参形态：包装回调代理
                if (chain.args.size >= 2 && chain.args[1] != null) {
                    val handler = chain.args[1]
                    val wrapped = Proxy.newProxyInstance(
                        cl,
                        handler.javaClass.interfaces,
                        InvocationHandler { _, method, args ->
                            if (args != null && args.isNotEmpty() && isResponseCarrier(method.name)) {
                                // 回调参数类型是宿主的强契约（KTX suspend 路径把 onNext 的实参存进
                                // 续体，在 onCompleted 里 resume 并强转）。只允许「同类对象」替换：
                                // 异类或空值一律不写回，否则宿主在 resume 处强转失败直接崩进程。
                                val original = args[0]
                                val mapped = injector.transform(method.name, original)
                                if (mapped != null && original != null &&
                                    original.javaClass.isInstance(mapped)
                                ) {
                                    args[0] = mapped
                                } else if (mapped !== original) {
                                    HookProbe.first(module, "seasonMoss:cbSkip", 3) {
                                        "${method.name} ${original?.javaClass?.simpleName}" +
                                            " <- ${mapped?.javaClass?.simpleName}"
                                    }
                                }
                            }
                            method.invoke(handler, *(args ?: emptyArray()))
                        },
                    )
                    return@intercept chain.proceed(arrayOf(req, wrapped))
                }
                // 单参挂起形态
                return@intercept chain.proceed()
            }
    }

    private fun isResponseCarrier(name: String): Boolean =
        name == "onNext" || name == "onCompleted" || name == "resumeWith" || name == "onSuccess"

    /** 一次 moss 观测的注入器：判定受限 → 拉数据 → 重建 reply。 */
    private class ReplyInjector(
        private val module: XposedModule,
        private val cl: ClassLoader,
        private val req: Any,
    ) {
        val reqDesc: String = runCatching {
            when {
                req.javaClass.methods.any { it.name == "getSeasonId" } ->
                    "seasonSections seasonId=${req.javaClass.methods.first { it.name == "getSeasonId" }.invoke(req)}"
                else -> "pageSectionEpisodes sectionId=" +
                    (req.javaClass.methods.firstOrNull { it.name == "getSectionId" }?.invoke(req) ?: "?") +
                    " aid=" + (req.javaClass.methods.firstOrNull { it.name == "getAid" }?.invoke(req) ?: "?")
            }
        }.getOrDefault("req parse failed")

        private fun reflect(name: String): Any? =
            req.javaClass.methods.firstOrNull { it.name == name }?.invoke(req)

        fun transform(via: String, reply: Any?): Any? {
            if (reply == null) return null
            val isSections = runCatching {
                reply.javaClass.methods.any { it.name == "getSectionsList" }
            }.getOrDefault(false)
            return if (isSections) {
                captureSections(via, reply)
                transformSections(via, reply)
            } else {
                transformEpisodes(via, reply)
            }
        }

        /**
         * 侦察：落盘原生 SeasonSectionsReply 并打印分区形状，取得「宿主应有形状」基线
         * （我们注入的是同一消息的自建版本，形状对齐才好比对）。每进程最多 3 份。
         */
        private fun captureSections(via: String, reply: Any) {
            val n = captured.incrementAndGet()
            if (n > 3) return
            val list = runCatching {
                reply.javaClass.methods.firstOrNull { it.name == "getSectionsList" }
                    ?.invoke(reply) as? List<*>
            }.getOrNull()
            if (list != null) {
                val shape = list.take(4).joinToString(" | ") { sec ->
                    sec?.let { s ->
                        runCatching {
                            fun call(name: String) =
                                s.javaClass.methods.firstOrNull { it.name == name }?.invoke(s)
                            "id=${call("getId")} sid=${call("getSectionId")} type=${call("getType")} " +
                                "eps=${call("getEpisodesCount")} eids=${call("getEpisodeIdsCount")} " +
                                "title=${call("getTitle")}"
                        }.getOrDefault("?")
                    } ?: "null"
                }
                HookProbe.first(module, "seasonMoss:shape", 3) { "via=$via n=${list.size} :: $shape" }
            }
            runCatching {
                val bytes = reply.javaClass.methods
                    .firstOrNull { m -> m.name == "toByteArray" && m.parameterTypes.isEmpty() }
                    ?.invoke(reply) as? ByteArray ?: return
                val dir = java.io.File(HostTargets.HOST_DATA_DIRS.first(), "unlock_capture")
                dir.mkdirs()
                val f = java.io.File(dir, "season_sections_$n.bin")
                f.outputStream().use { it.write(bytes) }
                HookProbe.first(module, "seasonMoss:capture", 3) { "${f.absolutePath} ${bytes.size}B via=$via" }
            }
        }

        private companion object {
            val captured = java.util.concurrent.atomic.AtomicInteger(0)
            val outCaptured = java.util.concurrent.atomic.AtomicInteger(0)
        }
        /** seasonSections：reply 无 sections 即注入。 */
        private fun transformSections(via: String, reply: Any): Any? {
            val existing = runCatching {
                (reply.javaClass.methods.firstOrNull { it.name == "getSectionsList" }
                    ?.invoke(reply) as? List<*>)?.size ?: -1
            }.getOrDefault(-1)
            if (existing > 0) return reply

            val seasonId = (reflect("getSeasonId") as? Number)?.toInt() ?: 0
            val epId = (reflect("getEpId") as? Number)?.toLong() ?: 0L
            val aid = (reflect("getAid") as? Number)?.toLong() ?: 0L

            val sections = executor.submit(
                java.util.concurrent.Callable { fetchSections(seasonId, epId, aid) },
            ).get(10, TimeUnit.SECONDS)

            if (sections == null) {
                HookProbe.first(module, "seasonMoss:sectionsFail", 3) { "via=$via seasonId=$seasonId" }
                return reply
            }
            val replyCls = cl.loadClass(HostTargets.SEASON_SECTIONS_REPLY_CLASS)
            val bytes = UnlockWire.buildSeasonSectionsReplyBytes(sections)
            captureOut(module, bytes)
            val rebuilt = replyCls.getMethod("parseFrom", ByteArray::class.java).invoke(null, bytes)
            HookProbe.first(module, "seasonMoss:sectionsInjected", 3) {
                "via=$via sections=${sections.size} episodes=${sections.sumOf { it.episodes.size }} ${bytes.size}B head=${sections.firstOrNull()?.let { "id=${it.id} sid=${it.sectionId} type=${it.type} eps=${it.episodes.size}" }}"
            }
            sections.forEach { sec ->
                sectionsBySectionId[sec.sectionId] = sec
                sec.episodes.forEach { ep -> seasonIdByAid[ep.aid] = sec.sectionId }
            }
            return rebuilt
        }

        /** 落盘本模块重建的字节，便于与原生实拍逐字段比对（每进程最多 3 份）。 */
        private fun captureOut(module: XposedModule, bytes: ByteArray) {
            val n = outCaptured.incrementAndGet()
            if (n > 3) return
            runCatching {
                val dir = java.io.File(HostTargets.HOST_DATA_DIRS.first(), "unlock_capture")
                dir.mkdirs()
                val f = java.io.File(dir, "season_sections_out_$n.bin")
                f.outputStream().use { it.write(bytes) }
                HookProbe.first(module, "seasonMoss:captureOut", 3) { "${f.absolutePath} ${bytes.size}B" }
            }
        }

        /** pageSectionEpisodes：reply 无 episodes 时按 section_id 取缓存分区重建。 */
        private fun transformEpisodes(via: String, reply: Any): Any? {
            val existing = runCatching {
                (reply.javaClass.methods.firstOrNull { it.name == "getEpisodesList" }
                    ?.invoke(reply) as? List<*>)?.size ?: -1
            }.getOrDefault(-1)
            if (existing > 0) return reply

            val sectionId = (reflect("getSectionId") as? Number)?.toInt() ?: 0
            val aid = (reflect("getAid") as? Number)?.toLong() ?: 0L
            val sec = sectionsBySectionId[sectionId]
                ?: aid.takeIf { it != 0L }?.let { seasonIdByAid[it] }?.let { sectionsBySectionId[it] }
                ?: return reply.also {
                    HookProbe.first(module, "seasonMoss:epsNoData", 3) { "via=$via sectionId=$sectionId" }
                }

            val replyCls = cl.loadClass(HostTargets.PAGE_SECTION_EPISODES_REPLY_CLASS)
            val bytes = UnlockWire.buildPageSectionEpisodesReplyBytes(sectionId, sec.episodes)
            val rebuilt = replyCls.getMethod("parseFrom", ByteArray::class.java).invoke(null, bytes)
            HookProbe.first(module, "seasonMoss:epsInjected", 3) {
                "via=$via sectionId=$sectionId episodes=${sec.episodes.size}"
            }
            return rebuilt
        }

        /** 拉 CN season JSON 并解析为分区列表（module 所在网络 CN 直连可达，匿名免签）。 */
        private fun fetchSections(seasonId: Int, epId: Long, aid: Long): List<SeasonSection>? {
            val query = when {
                seasonId != 0 -> "season_id=$seasonId"
                epId != 0L -> "ep_id=$epId"
                else -> return null
            }
            return runCatching {
                val conn = java.net.URL("https://api.bilibili.com/pgc/view/web/season?$query")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 BiliDroid/android")
                val stream = try {
                    conn.inputStream
                } catch (e: java.io.IOException) {
                    conn.errorStream ?: throw e
                }
                val body = (if (conn.contentEncoding == "gzip") java.util.zip.GZIPInputStream(stream) else stream)
                    .bufferedReader().use { it.readText() }
                conn.disconnect()
                SeasonParser.parseSections(body)
            }.onFailure {
                module.info("$TAG: season fetch failed: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }
}
