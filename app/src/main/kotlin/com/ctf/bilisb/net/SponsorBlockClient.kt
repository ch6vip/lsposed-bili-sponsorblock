package com.ctf.bilisb.net

import android.util.Log
import com.ctf.bilisb.model.SponsorBlockConfig
import com.ctf.bilisb.model.SponsorBlockQuery
import com.ctf.bilisb.model.SponsorBlockSubmission
import com.ctf.bilisb.model.SponsorSegment
import com.ctf.bilisb.util.HashUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

interface SponsorBlockApi {
    fun fetchSkipSegments(query: SponsorBlockQuery, ignoreCache: Boolean = false): SponsorBlockClient.FetchResult
    fun submitSegment(submission: SponsorBlockSubmission, ignoreCache: Boolean = true): SponsorBlockClient.SubmitResult
}

class SponsorBlockClient(
    private val config: SponsorBlockConfig = SponsorBlockConfig(),
    /**
     * 日志出口。默认走 [Log.w];单测里注入空实现即可(android.util.Log 在 JVM 单测中未实现会抛异常)。
     */
    private val logger: (String) -> Unit = { message -> Log.w(TAG, message) },
) : SponsorBlockApi {
    data class FetchResult(
        val statusCode: Int,
        val body: String?,
        val segments: List<SponsorSegment>,
        /** 200 但 body 解析失败(非 JSON/截断/CDN 错误页):结果不可信,调用方不应缓存。 */
        val parseFailed: Boolean = false,
    ) {
        /** 2xx 即视为成功(不要只认 200,服务端可能返回 201/204)。 */
        val isSuccess: Boolean get() = statusCode in 200..299
    }

    data class SubmitResult(
        val statusCode: Int,
        val body: String?,
        /** 实际生效的 HTTP 方法:POST / GET(POST 不被支持时降级)。便于真机确认服务端形态。 */
        val method: String = "POST",
    ) {
        /** 2xx 即视为成功(不要只认 200)。 */
        val isSuccess: Boolean get() = statusCode in 200..299
    }

    /**
     * 拉取与提交各用一条独立的单线程队列:拉取被慢响应占住时,提交不会排在它后面。
     * 线程都是 daemon,宿主进程退出即回收;模块关闭时可调用 [close] 主动停掉。
     */
    private val fetchExecutor: ExecutorService = newSingleThreadExecutor("SponsorBlock-fetch")
    private val submitExecutor: ExecutorService = newSingleThreadExecutor("SponsorBlock-submit")
    private val closed = AtomicBoolean(false)

    fun endpointForBvid(bvid: String): String {
        val prefix = HashUtils.videoIdHashPrefix(bvid)
        return "${config.serverAddress.trimEnd('/')}/api/skipSegments/$prefix"
    }

    /**
     * 拉取片段。失败(网络 IO 异常)时最多重试 2 次(共 3 次尝试);
     * 最终仍失败返回 statusCode=-1,不抛异常。
     */
    override fun fetchSkipSegments(query: SponsorBlockQuery, ignoreCache: Boolean): FetchResult {
        if (closed.get()) {
            log("fetch skipped: client closed")
            return FETCH_FAILURE
        }
        return runOn(fetchExecutor, "fetch", FETCH_FAILURE) {
            retryRequest(
                maxRetries = FETCH_MAX_ATTEMPTS,
                onFailure = { FETCH_FAILURE },
            ) {
                fetchSegmentsInternal(query, ignoreCache)
            }
        }
    }

    private fun fetchSegmentsInternal(query: SponsorBlockQuery, ignoreCache: Boolean): FetchResult {
        val url = URL(endpointForBvid(query.bvid))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Origin", ORIGIN_HEADER)
            setRequestProperty("X-EXT-VERSION", EXT_VERSION_HEADER)
            if (ignoreCache) {
                setRequestProperty("cache-control", "no-cache")
                setRequestProperty("X-SKIP-CACHE", "1")
            }
        }

        return try {
            val status = conn.responseCode
            val body = readBody(conn, status, "fetch")
            log("fetch GET $url -> status=$status body=${body?.take(LOG_BODY_PREVIEW_CHARS)}")
            // 解析失败(如 CDN 错误页 body 不是 JSON)按空结果处理,不触发重试,但标记 parseFailed
            val parsed = runCatching { parseSegmentsForVideo(query.bvid, body) }
            val segments = parsed
                .onFailure { log("parse segments failed status=$status: ${it.message}") }
                .getOrElse { emptyList() }
            FetchResult(status, body, segments, parseFailed = parsed.isFailure)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 提交片段。
     *
     * 走官方协议的 `POST /api/skipSegments`(form-urlencoded);服务端返回 405/501
     * (不支持 POST)时降级为原始 GET 形态再发一次。
     *
     * **刻意不自动重试**:提交是写操作,IO 异常时无法判断服务端是否已经落库,
     * 重试会造成重复提交;只有明确收到 405/501(即服务端没有接受本次 POST)才降级重发。
     */
    override fun submitSegment(submission: SponsorBlockSubmission, ignoreCache: Boolean): SubmitResult {
        if (closed.get()) {
            log("submit skipped: client closed")
            return SUBMIT_FAILURE
        }
        return runOn(submitExecutor, "submit", SUBMIT_FAILURE) {
            submitSegmentInternal(submission, ignoreCache)
        }
    }

    private fun submitSegmentInternal(submission: SponsorBlockSubmission, ignoreCache: Boolean): SubmitResult {
        val postResult = sendSubmitRequest(
            method = METHOD_POST,
            url = submitEndpoint(),
            body = buildSubmitBody(submission),
            ignoreCache = ignoreCache,
        )
        if (postResult.statusCode == HTTP_METHOD_NOT_ALLOWED || postResult.statusCode == HTTP_NOT_IMPLEMENTED) {
            // 兼容旧服务端:POST 不被支持时退回原来的 GET 形态。
            log(
                "POST submit unsupported status=${postResult.statusCode} " +
                    "body=${postResult.body?.take(LOG_BODY_PREVIEW_CHARS)}, fallback to GET",
            )
            val getResult = sendSubmitRequest(
                method = METHOD_GET,
                url = buildSubmitUrl(submission),
                body = null,
                ignoreCache = ignoreCache,
            )
            log("GET fallback submit -> status=${getResult.statusCode} body=${getResult.body?.take(LOG_BODY_PREVIEW_CHARS)}")
            return getResult
        }
        return postResult
    }

    /** 真正发一次提交请求;POST 时把 form body 写进输出流。 */
    private fun sendSubmitRequest(
        method: String,
        url: String,
        body: String?,
        ignoreCache: Boolean,
    ): SubmitResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Origin", ORIGIN_HEADER)
            setRequestProperty("X-EXT-VERSION", EXT_VERSION_HEADER)
            if (ignoreCache) {
                setRequestProperty("cache-control", "no-cache")
                setRequestProperty("X-SKIP-CACHE", "1")
            }
            doInput = true
        }

        return try {
            if (body != null) {
                val payload = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", FORM_CONTENT_TYPE)
                conn.setFixedLengthStreamingMode(payload.size)
                conn.outputStream.use { it.write(payload) }
            }
            val status = conn.responseCode
            val responseBody = readBody(conn, status, "submit($method)")
            // 日志里的 URL 必须脱敏:userID 是提交身份凭据,GET 降级时它拼在 query 里,
            // 明文进 logcat 会被复制 LSPosed 日志求助的用户一起带走
            log("submit $method ${redactUserId(url)} -> status=$status body=${responseBody?.take(LOG_BODY_PREVIEW_CHARS)}")
            SubmitResult(status, responseBody, method)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 读取响应体,带 [MAX_RESPONSE_BYTES] 上限保护:超过上限就当截断处理并记 warn,避免超大响应把进程撑爆。
     * 非 2xx 读 errorStream(errorStream 为 null 时返回 null)。
     */
    private fun readBody(conn: HttpURLConnection, status: Int, label: String): String? {
        val stream: InputStream = if (status in 200..299) {
            conn.inputStream
        } else {
            conn.errorStream ?: return null
        }
        return stream.use { input ->
            val buffer = ByteArray(READ_BUFFER_BYTES)
            val out = ByteArrayOutputStream()
            var truncated = false
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                val remaining = MAX_RESPONSE_BYTES - out.size()
                if (read > remaining) {
                    out.write(buffer, 0, remaining.coerceAtLeast(0))
                    truncated = true
                    break
                }
                out.write(buffer, 0, read)
            }
            if (truncated) {
                log("$label response body exceeded ${MAX_RESPONSE_BYTES / (1024 * 1024)}MB, truncated")
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    /**
     * 单方向的重试执行器(目前只有拉取使用,提交刻意不重试)。
     *
     * 只有网络 IO 异常([IOException],含 [java.net.SocketTimeoutException])才重试;
     * [MalformedURLException] 虽然是 IOException 子类,但属于参数/配置错误,重试不会变好;
     * [IllegalArgumentException]、InterruptedException 等其它异常同样不重试。
     * 被中断时恢复中断标记并返回 [onFailure],不吞掉中断。
     *
     * @param maxRetries 总尝试次数(传 1 表示不重试)
     */
    fun <T> retryRequest(maxRetries: Int, onFailure: (Exception) -> T, block: () -> T): T {
        val attempts = maxRetries.coerceAtLeast(1)
        var lastException: Exception? = null
        for (attempt in 0 until attempts) {
            try {
                return block()
            } catch (e: InterruptedException) {
                // 中断是调用方要求停止,不是可重试的失败:恢复中断标记后立即返回失败结果。
                Thread.currentThread().interrupt()
                log("request interrupted: ${e.message}")
                return onFailure(e)
            } catch (e: MalformedURLException) {
                // URL 非法 = 参数/配置错误,重试没有意义。
                log("request aborted (malformed url): ${e.message}")
                return onFailure(e)
            } catch (e: IOException) {
                // 只有网络 IO 异常才重试(超时、连接失败、读响应中途断开等)。
                lastException = e
                log("request attempt ${attempt + 1}/$attempts failed: ${e.message}")
                if (attempt < attempts - 1) {
                    try {
                        Thread.sleep((attempt + 1) * RETRY_BASE_DELAY_MS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        log("retry delay interrupted: ${interrupted.message}")
                        return onFailure(interrupted)
                    }
                }
            } catch (e: Exception) {
                // 其它异常(IllegalArgumentException / JSONException 等)不重试。
                log("request failed without retry (${e.javaClass.simpleName}): ${e.message}")
                return onFailure(e)
            }
        }
        log("request failed after $attempts attempts: ${lastException?.message}")
        return onFailure(lastException ?: IOException("request failed"))
    }

    /**
     * 把请求丢到对应方向的 executor 上执行并等结果(单线程队列 = 同方向请求天然串行)。
     *
     * 注意:当前调用方(`SponsorBlockController`)仍在自己的单线程上顺序调用本方法,
     * 要让"提交不被慢拉取阻塞"真正生效,调用方需要分别用两个 executor 或改成异步入口。
     */
    private fun <T> runOn(executor: ExecutorService, label: String, onFailure: T, block: () -> T): T {
        val future = try {
            executor.submit(Callable { block() })
        } catch (e: RejectedExecutionException) {
            // executor 已关闭:不再起新线程,退回调用线程同步执行,避免功能静默失效。
            log("$label executor rejected task, running inline: ${e.message}")
            return runCatching(block).getOrElse { onFailure }
        }
        return try {
            future.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log("$label interrupted while waiting: ${e.message}")
            onFailure
        } catch (e: ExecutionException) {
            log("$label failed: ${e.cause?.message}")
            onFailure
        }
    }

    fun parseSegmentsForVideo(bvid: String?, raw: String?): List<SponsorSegment> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        val buckets = JSONArray(raw)
        val result = mutableListOf<SponsorSegment>()
        for (i in 0 until buckets.length()) {
            val bucket = buckets.optJSONObject(i) ?: continue
            val videoId = bucket.optString("videoID", "")
            if (bvid != null && videoId != bvid) {
                continue
            }
            val segments = bucket.optJSONArray("segments") ?: continue
            for (j in 0 until segments.length()) {
                val segment = parseSegment(segments.optJSONObject(j) ?: continue) ?: continue
                if (segment.category !in config.enabledCategories) {
                    continue
                }
                if (segment.actionType !in config.enabledActionTypes) {
                    continue
                }
                result += segment
            }
            // 接口返回的是"该 hash 前缀下所有视频"的桶,命中目标 videoID 后剩下的桶与目标无关,
            // 直接 break,不再解析后面的元素(省 CPU,也避免被无关的大数组拖慢)。
            if (bvid != null) {
                break
            }
        }
        return result.sortedBy { it.startMs }
    }

    private fun parseSegment(json: JSONObject): SponsorSegment? {
        val segmentArray = json.optJSONArray("segment") ?: return null
        if (segmentArray.length() < 2) {
            return null
        }

        // org.json 的 optString 会把 JSON null 变成字面量 "null",所以统一走 optStringOrNull。
        val category = optStringOrNull(json, "category")?.takeIf { it.isNotBlank() } ?: DEFAULT_CATEGORY
        val actionType = optStringOrNull(json, "actionType")?.takeIf { it.isNotBlank() } ?: DEFAULT_ACTION_TYPE
        // UUID 是去重 key(跳过记录/提交去重都依赖它),缺失或为 null 时丢弃该片段。
        val uuid = optStringOrNull(json, "UUID")?.takeIf { it.isNotBlank() }
            ?: optStringOrNull(json, "uuid")?.takeIf { it.isNotBlank() }
            ?: return null

        // SponsorBlock API 返回秒,播放器 API 用毫秒,这里统一换算成毫秒。
        val videoDurationSec = json.optDouble("videoDuration", 0.0)
        val videoDurationMs = if (videoDurationSec.isFinite() && videoDurationSec > 0.0) {
            (videoDurationSec * 1000.0).toLong()
        } else {
            0L
        }

        val range = sanitizeSegmentRange(segmentArray, videoDurationMs) ?: return null

        return SponsorSegment(
            category = category,
            actionType = actionType,
            segment = range,
            uuid = uuid,
            videoDuration = if (videoDurationSec.isFinite()) videoDurationSec else 0.0,
            locked = json.optBoolean("locked", false),
            votes = json.optLong("votes", 0L),
            description = optStringOrNull(json, "description") ?: "",
        )
    }

    /**
     * 单条片段的健全性过滤,集中在这里(返回 null 表示丢弃):
     *  1. start/end 非有限值(NaN / Infinity,例如 JSON 里的 `1e400`)—— 直接 `toLong` 会溢出成 Long.MAX_VALUE;
     *  2. start/end 超过 [MAX_SEGMENT_SECONDS] —— 明显是脏数据,也防 `*1000` 之后溢出;
     *  3. `startMs < 0`;
     *  4. `endMs <= startMs`;
     *  5. 片段短于 [MIN_SEGMENT_DURATION_MS](250ms)—— 跳过/静音这类操作对极短片段没有意义;
     *  6. 已知视频总时长时,`endMs` 超出总时长(留 [VIDEO_DURATION_TOLERANCE_MS] 误差余量)—— 服务端 duration 可能是旧值。
     */
    private fun sanitizeSegmentRange(segmentArray: JSONArray, videoDurationMs: Long): LongArray? {
        val startSec = segmentArray.optDouble(0, Double.NaN)
        val endSec = segmentArray.optDouble(1, Double.NaN)
        if (!startSec.isFinite() || !endSec.isFinite()) {
            return null
        }
        if (startSec > MAX_SEGMENT_SECONDS || endSec > MAX_SEGMENT_SECONDS) {
            return null
        }
        val startMs = (startSec * 1000.0).toLong()
        val endMs = (endSec * 1000.0).toLong()
        if (startMs < 0L) {
            return null
        }
        if (endMs <= startMs) {
            return null
        }
        if (endMs - startMs < MIN_SEGMENT_DURATION_MS) {
            return null
        }
        if (videoDurationMs > 0L && endMs > videoDurationMs + VIDEO_DURATION_TOLERANCE_MS) {
            return null
        }
        return longArrayOf(startMs, endMs)
    }

    /**
     * JSON null 安全的字符串读取:显式 null 与键缺失都返回 null。
     * (org.json 的 `isNull` 对"键不存在"同样返回 true,正好符合这里的语义。)
     */
    private fun optStringOrNull(json: JSONObject, key: String): String? {
        return if (json.isNull(key)) null else json.optString(key)
    }

    /*
     * 拉取接口不带任何 query 参数(历史注记,参数实测全部 400)。
     * 真机实测（6.5.0 + `https://bsbsb.top`）：
     *   `?videoID=...&cid=...&actionType=skip` → HTTP 400
     *   `?videoID=...` / `?cid=...`          → HTTP 400
     *   `?actionType=skip` / 无 query         → HTTP 200（返回该 hash 前缀下所有视频的片段）
     *
     * 官方 SponsorBlock 协议里 `/api/skipSegments/{prefix}` 只按前缀返回、由客户端按 videoID 过滤，
     * 并不存在 `videoID` / `cid` 这两个参数（[parseSegmentsForVideo] 负责过滤）。
     * 另外不带 `actionType=skip` 还能拿到 `actionType=poi` 的 POI 片段，供进度条圆点标记使用。
     */

    /**
     * 降级用的 GET 提交 URL：保持原有的参数形态不变，仅在服务端不支持 POST(405/501)时使用。
     */
    fun buildSubmitUrl(submission: SponsorBlockSubmission): String {
        val query = buildString {
            append("?userID=").append(encode(submission.userId))
            append("&videoID=").append(encode(submission.bvid))
            append("&cid=").append(submission.cid)
            append("&category=").append(encode(submission.category))
            append("&startTime=").append(formatSeconds(submission.startMs))
            append("&endTime=").append(formatSeconds(submission.endMs))
            append("&videoDuration=").append(formatSeconds(submission.videoDurationMs))
            if (submission.epId > 0) {
                append("&epId=").append(submission.epId)
            }
        }
        return submitEndpoint() + query
    }

    /**
     * 官方协议的 POST body(`application/x-www-form-urlencoded`,值一律 percent-encode)。
     * `userAgent` 必填、`actionType` 固定 `skip`、`service` 固定 `bilibili`。
     */
    fun buildSubmitBody(submission: SponsorBlockSubmission): String {
        val fields = mutableListOf(
            "userID" to submission.userId,
            "userAgent" to SUBMIT_USER_AGENT,
            "videoID" to submission.bvid,
        )
        if (submission.cid > 0) {
            fields += "cid" to submission.cid.toString()
        }
        fields += "category" to submission.category
        fields += "actionType" to SUBMIT_ACTION_TYPE
        fields += "startTime" to formatSeconds(submission.startMs)
        fields += "endTime" to formatSeconds(submission.endMs)
        fields += "videoDuration" to formatSeconds(submission.videoDurationMs)
        fields += "service" to SUBMIT_SERVICE
        if (submission.epId > 0) {
            fields += "epId" to submission.epId.toString()
        }
        return fields.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    }

    private fun submitEndpoint(): String = "${config.serverAddress.trimEnd('/')}/api/skipSegments"

    private fun encode(value: String): String {
        return try {
            URLEncoder.encode(value, Charsets.UTF_8.name())
        } catch (e: UnsupportedEncodingException) {
            // UTF-8 必然存在,这里只是 Java API 的编译期要求;兜底返回原值,
            // 避免把"编码不支持"伪装成 IO 失败而触发无意义的重试。
            log("encode failed for '${value.take(32)}': ${e.message}")
            value
        }
    }

    private fun formatSeconds(valueMs: Long): String {
        return String.format(Locale.US, "%.3f", valueMs / 1000.0)
    }

    /** 日志脱敏:把 URL query 里的 userID 值替换成前 4 位 + 省略号(保留排障所需的最小信息)。 */
    private fun redactUserId(url: String): String {
        return USER_ID_QUERY_REGEX.replace(url) { match ->
            val value = match.groupValues[2]
            "${match.groupValues[1]}${value.take(4)}…"
        }
    }

    private fun log(message: String) {
        logger(message)
    }

    /** 停掉拉取/提交 executor(幂等)。关闭后新的请求会直接返回失败结果。 */
    fun close() {
        shutdown()
    }

    /** [close] 的别名,兼容可能用 `shutdown()` 命名的调用方。 */
    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        fetchExecutor.shutdownNow()
        submitExecutor.shutdownNow()
        log("client closed")
    }

    private fun newSingleThreadExecutor(name: String): ExecutorService {
        return Executors.newSingleThreadExecutor(
            object : ThreadFactory {
                override fun newThread(r: Runnable): Thread {
                    return Thread(r, name).apply { isDaemon = true }
                }
            },
        )
    }

    companion object {
        private const val TAG = "SponsorBlockClient"

        /** 提交接口固定使用的 userAgent(官方协议必填)。 */
        const val SUBMIT_USER_AGENT = "Bili2233/1.0 (LSPosed)"

        /** 官方协议里的 service 字段(B 站)。 */
        const val SUBMIT_SERVICE = "bilibili"

        /** 提交的 actionType 固定为 skip(跳过类片段)。 */
        const val SUBMIT_ACTION_TYPE = "skip"

        private const val METHOD_POST = "POST"
        private const val METHOD_GET = "GET"
        private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
        private const val ORIGIN_HEADER = "BiliRoamingX"
        private const val EXT_VERSION_HEADER = "1.27.3"
        private const val DEFAULT_CATEGORY = "sponsor"
        private const val DEFAULT_ACTION_TYPE = "skip"

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000

        /** 拉取总尝试次数 = 首次 + 最多 2 次重试(降低重试的时间成本)。 */
        private const val FETCH_MAX_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 500L

        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_NOT_IMPLEMENTED = 501

        /** 响应体读取上限 4MB,超出截断并记 warn。 */
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val LOG_BODY_PREVIEW_CHARS = 200

        /** 日志脱敏用:匹配 query 里的 userID 参数(名、值两组)。 */
        private val USER_ID_QUERY_REGEX = Regex("""(userID=)([^&]*)""")

        /** 短于 250ms 的片段直接丢弃(跳过/静音没有意义,还会让进度条抖动)。 */
        private const val MIN_SEGMENT_DURATION_MS = 250L

        /** 允许片段末端超出视频总时长的误差(服务端 duration 可能是旧值)。 */
        private const val VIDEO_DURATION_TOLERANCE_MS = 5_000L

        /** 片段时间的可接受上限(24 小时),超过视为脏数据,同时防 `*1000` 溢出。 */
        private const val MAX_SEGMENT_SECONDS = 86_400.0

        private val FETCH_FAILURE = FetchResult(-1, null, emptyList())
        private val SUBMIT_FAILURE = SubmitResult(-1, null)
    }
}
