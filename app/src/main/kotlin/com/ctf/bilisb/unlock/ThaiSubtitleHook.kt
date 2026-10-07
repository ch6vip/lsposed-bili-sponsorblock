package com.ctf.bilisb.unlock

import android.net.Uri
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 泰区多语言字幕及简中字幕自动生成（对齐 BiliRoaming C0260li 与 auto_generate_subtitle）。
 *
 * 1. 记录正在播放的 cid -> epId 对应关系；
 * 2. 拦截 DmMoss.dmView / executeDmView 弹幕视图协议；
 * 3. 当视频为泰区/东南亚区域番剧时，向东南亚网关 `/intl/gateway/v2/app/subtitle?ep_id=...` 拉取官方多语言字幕；
 * 4. 当字幕列表中含有繁体中文（zh-Hant）且缺乏简体中文（zh-CN）时，自动派生合成 `zh_converter=t2cn` 的「简中（生成）」字幕项；
 * 5. 使用 WireSplice / WireWriter 零损注入回 `DmViewReply.subtitle`。
 */
object ThaiSubtitleHook {

    private const val DM_MOSS_CLASS = "com.bapis.bilibili.community.service.dm.v1.DMMoss"
    private const val DM_MOSS_ALT_CLASS = "com.bapis.bilibili.community.service.dm.v1.DmMoss"
    private const val DM_VIEW_REPLY_CLASS = "com.bapis.bilibili.community.service.dm.v1.DmViewReply"

    /** cid -> epId 缓存（供 DmView 请求按 cid 反查 epId 获取泰区字幕）。 */
    private val cidToEpId = ConcurrentHashMap<Long, Long>()
    private val installed = AtomicBoolean(false)

    data class SubtitleEntry(
        val id: Long,
        val lan: String,
        val lanDoc: String,
        val url: String,
    )

    fun recordCidEpId(cid: Long, epId: Long) {
        if (cid > 0 && epId > 0) {
            cidToEpId[cid] = epId
        }
    }

    fun install(module: XposedModule, cl: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        val mossCls = runCatching { cl.loadClass(DM_MOSS_CLASS) }
            .getOrElse { runCatching { cl.loadClass(DM_MOSS_ALT_CLASS) }.getOrNull() }

        if (mossCls == null) {
            HookProbe.first(module, "unlock:dmMossNotFound", 1) { "DMMoss class not found" }
            return
        }

        for (m in mossCls.declaredMethods.filter { it.name in listOf("dmView", "executeDmView") }) {
            runCatching { m.isAccessible = true }
            runCatching { module.deoptimize(m) }
            hookDmViewMethod(module, cl, m)
            HookProbe.ok(module, "unlock:dmViewHooked", "${m.name}(${m.parameterTypes.size} args)")
        }
    }

    private fun hookDmViewMethod(module: XposedModule, cl: ClassLoader, method: Method) {
        module.hook(method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val config = UnlockConfig.load(module)
                if (!config.enabled || (!config.thSubtitle && !config.autoGenerateSubtitle)) {
                    return@intercept chain.proceed()
                }

                // 提取请求中的 oid (cid)
                val req = chain.args.firstOrNull()
                val oid = extractOid(req)

                // 2 参为异步回调，1 参为同步返回
                if (chain.args.size >= 2) {
                    val origCallback = chain.args[1]
                    if (origCallback != null) {
                        val newArgs = chain.args.toTypedArray()
                        newArgs[1] = wrapCallback(module, cl, origCallback, oid, config)
                        chain.proceed(newArgs)
                    } else {
                        chain.proceed()
                    }
                } else {
                    val result = chain.proceed()
                    transformDmViewReply(module, cl, result, oid, config) ?: result
                }
            }
    }

    private fun extractOid(req: Any?): Long {
        if (req == null) return 0L
        return runCatching {
            req.javaClass.methods.firstOrNull { it.name == "getOid" && it.parameterTypes.isEmpty() }
                ?.invoke(req) as? Long
        }.getOrNull() ?: 0L
    }

    private fun wrapCallback(
        module: XposedModule,
        cl: ClassLoader,
        origCallback: Any,
        oid: Long,
        config: UnlockConfig.Config,
    ): Any {
        val interfaces = origCallback.javaClass.interfaces
        if (interfaces.isEmpty()) return origCallback
        return Proxy.newProxyInstance(cl, interfaces) { _, method, args ->
            val transformedArgs = if (args != null && args.isNotEmpty()) {
                val copy = args.clone()
                for (i in copy.indices) {
                    val arg = copy[i]
                    if (arg != null && isDmViewReply(arg)) {
                        copy[i] = transformDmViewReply(module, cl, arg, oid, config) ?: arg
                    }
                }
                copy
            } else args
            method.invoke(origCallback, *(transformedArgs ?: emptyArray()))
        }
    }

    private fun isDmViewReply(obj: Any): Boolean {
        return obj.javaClass.name.endsWith("DmViewReply")
    }

    /**
     * 将 DmViewReply 的 protobuf 响应体进行字幕提取、生成与注入。
     */
    fun transformDmViewReply(
        module: XposedModule,
        cl: ClassLoader,
        reply: Any?,
        oid: Long,
        config: UnlockConfig.Config,
    ): Any? {
        if (reply == null) return null
        return runCatching {
            val toByteArray = reply.javaClass.methods.firstOrNull { it.name == "toByteArray" && it.parameterTypes.isEmpty() }
                ?: return@runCatching null
            val rawBytes = toByteArray.invoke(reply) as? ByteArray ?: return@runCatching null

            val epId = if (oid > 0) cidToEpId[oid] ?: 0L else 0L
            val patchedBytes = injectSubtitles(rawBytes, epId, config)
            if (patchedBytes.contentEquals(rawBytes)) return@runCatching reply

            val parseFrom = cl.loadClass(DM_VIEW_REPLY_CLASS)
                .getMethod("parseFrom", ByteArray::class.java)
            parseFrom.invoke(null, patchedBytes)
        }.getOrElse { reply }
    }

    /**
     * 对 DmViewReply 的字节流进行字幕注入（纯字节处理，免反射异常）。
     */
    fun injectSubtitles(
        rawBytes: ByteArray,
        epId: Long,
        config: UnlockConfig.Config,
    ): ByteArray {
        val existingSubtitleBytes = WireSplice.firstMessage(rawBytes, 3) // field 3 = subtitle (VideoSubtitle)
        val currentSubtitles = parseSubtitleItems(existingSubtitleBytes)

        val newSubtitles = mutableListOf<SubtitleEntry>()
        newSubtitles.addAll(currentSubtitles)

        // 1. 若配置了泰区字幕且本地无字幕，向泰区服务器拉取
        if (config.thSubtitle && epId > 0 && currentSubtitles.isEmpty()) {
            val thServer = config.serverTh.ifBlank {
                config.servers.firstOrNull { it.area == "th" }?.baseUrl ?: ""
            }
            if (thServer.isNotBlank()) {
                val fetched = fetchThaiSubtitles(thServer, epId)
                for (item in fetched) {
                    if (newSubtitles.none { it.lan == item.lan }) {
                        newSubtitles.add(item)
                    }
                }
            }
        }

        // 2. 若启用了根据繁体自动生成简中字幕
        if (config.autoGenerateSubtitle) {
            val hasHant = newSubtitles.firstOrNull { it.lan.startsWith("zh-Han", ignoreCase = true) || it.lan.startsWith("zh-TW", ignoreCase = true) || it.lan.startsWith("zh-HK", ignoreCase = true) }
            val hasHans = newSubtitles.any { it.lan.equals("zh-CN", ignoreCase = true) || it.lan.equals("zh-Hans", ignoreCase = true) }

            if (hasHant != null && !hasHans) {
                val genUrl = if (hasHant.url.contains('?')) {
                    "${hasHant.url}&zh_converter=t2cn"
                } else {
                    "${hasHant.url}?zh_converter=t2cn"
                }
                newSubtitles.add(
                    SubtitleEntry(
                        id = hasHant.id + 1,
                        lan = "zh-CN",
                        lanDoc = "简中（生成）",
                        url = genUrl,
                    )
                )
            }
        }

        if (newSubtitles == currentSubtitles) {
            return rawBytes
        }

        val updatedVideoSubtitle = buildVideoSubtitle(existingSubtitleBytes, newSubtitles)
        return WireSplice.transformMessage(rawBytes, 3) { updatedVideoSubtitle }
    }

    private fun parseSubtitleItems(videoSubtitleBytes: ByteArray?): List<SubtitleEntry> {
        if (videoSubtitleBytes == null || videoSubtitleBytes.isEmpty()) return emptyList()
        val items = mutableListOf<SubtitleEntry>()
        val itemBytesList = WireSplice.allMessages(videoSubtitleBytes, 3) // field 3 = repeated SubtitleItem
        for (itemBytes in itemBytesList) {
            var id = 0L
            var lan = ""
            var lanDoc = ""
            var url = ""
            for (elem in WireSplice.parse(itemBytes)) {
                when (elem.field) {
                    1 -> if (elem.wireType == 0) id = elem.varint()
                    3 -> if (elem.wireType == 2) lan = elem.payload().decodeToString()
                    4 -> if (elem.wireType == 2) lanDoc = elem.payload().decodeToString()
                    5 -> if (elem.wireType == 2) url = elem.payload().decodeToString()
                }
            }
            if (lan.isNotEmpty() && url.isNotEmpty()) {
                items.add(SubtitleEntry(id, lan, lanDoc, url))
            }
        }
        return items
    }

    private fun buildVideoSubtitle(existingBytes: ByteArray?, subtitles: List<SubtitleEntry>): ByteArray {
        val w = WireWriter()
        val defaultLan = subtitles.firstOrNull { it.lan == "zh-CN" }?.lan ?: subtitles.firstOrNull()?.lan ?: "zh-CN"
        val defaultDoc = subtitles.firstOrNull { it.lan == "zh-CN" }?.lanDoc ?: subtitles.firstOrNull()?.lanDoc ?: "中文（简体）"

        w.stringField(1, defaultLan)
        w.stringField(2, defaultDoc)

        for (item in subtitles) {
            val itemWriter = WireWriter()
            itemWriter.int64Field(1, item.id)
            itemWriter.stringField(2, item.id.toString())
            itemWriter.stringField(3, item.lan)
            itemWriter.stringField(4, item.lanDoc)
            itemWriter.stringField(5, item.url)
            itemWriter.int32Field(7, 1) // type
            itemWriter.stringField(8, item.lanDoc.take(2)) // lan_doc_brief
            w.messageField(3, itemWriter.toByteArray())
        }
        return w.toByteArray()
    }

    private fun fetchThaiSubtitles(server: String, epId: Long): List<SubtitleEntry> {
        val url = server.trimEnd('/') + "/intl/gateway/v2/app/subtitle?ep_id=$epId"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "Bilibili Freedoooooom/MarkII")
            }
            val code = conn.responseCode
            if (code != 200) return@runCatching emptyList()
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            if (json.optInt("code", -1) != 0) return@runCatching emptyList()

            val data = json.optJSONObject("data") ?: return@runCatching emptyList()
            val array = data.optJSONArray("subtitles") ?: return@runCatching emptyList()
            val result = mutableListOf<SubtitleEntry>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optLong("id")
                val key = obj.optString("key")
                val title = obj.optString("title")
                val subUrl = obj.optString("url")
                if (key.isNotEmpty() && subUrl.isNotEmpty()) {
                    result.add(SubtitleEntry(id, key, title, subUrl))
                }
            }
            result
        }.getOrDefault(emptyList())
    }
}
