package com.ctf.bilisb.unlock

import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HostTargets
import io.github.libxposed.api.XposedModule

/**
 * 传输无关的解锁核心（P0 抽出，见 docs/UNLOCK_PLAN.md）。
 *
 * 宿主 6.6.0 有两套互相独立的播放传输：MOSS（`PlayerMoss.playViewUnite`，[PlayViewHook]）
 * 与 K/gRPC（`xr1.j`，[KPlayViewHook]，离线下载引擎走它）。但「受限 → 漫游取地址 →
 * 用宿主类重建响应」这一段**与传输无关**：输入是请求事实 + 可选的原响应字节，
 * 输出是重建后的响应字节。本文件承接这一段，两个 Hook 都退化成薄适配器
 * （取事实 / 包装回调 / 限时 / Toast / 探针），不再各写一份网络与重建。
 *
 * **为什么以字节为边界**：`ResponseReconstructor` 只要求「宿主类 + 字节」，
 * 而宿主对象由各传输自己给出（MOSS 回调里直接是 `PlayViewUniteReply` 实例）。
 * 适配器负责 宿主对象 ↔ 字节 的转换，核心因此不持有任何宿主对象引用——
 * 这正是 K 侧将来能直接复用的前提。
 *
 * 探针标签与文案保持重构前逐字不变（真机 logcat 是我们的验证证据）：
 * 本文件发 `unlock:ak` / `unlock:noAccessKey` / `unlock:roamFailed` /
 * `unlock:parseFailed` / `unlock:uposReplaced`；`unlock:rebuildFailed` /
 * `unlock:proxied` / `unlock:reserialized` 依赖宿主对象或失败原因文案，留在适配器里。
 */
object RoamingUnlockCore {

    /** 单次漫游请求的 HTTP 超时：必须显著小于适配器 3s 的等待上限，否则等不到结果就先超时。 */
    private const val DEFAULT_TIMEOUT_MS = 2500

    /**
     * 一次请求的解锁事实（传输无关形态）。
     *
     * [epId] / [cid] 为 0 表示请求侧没带（重定向、下载取地址场景都是这样），
     * 由适配器用响应侧的值补齐后再送进来；[isDownload] 只影响判定与异常兜底，不进查询串。
     */
    data class UnlockRequestFacts(
        val epId: Long,
        val cid: Long,
        val seasonId: String,
        val qn: Int,
        val fnver: Int,
        val fnval: Int,
        val forceHost: Int,
        val fourk: Boolean,
        val isDownload: Boolean,
    )

    /**
     * 七参数查询串（纯函数，与 [RoamingClient.buildQuery] 参数名/顺序逐字一致）。
     *
     * 这里刻意保留一份**独立表达式**而不是转调 [RoamingClient.buildQuery]：转调虽然
     * 「不可能漂移」，却让「事实 → 查询串」的映射藏进另一层，边界反而看不见。
     * 两者逐字相等由 `RoamingUnlockCoreTest` 钉住——改动任一处的顺序或参数名即红。
     */
    fun buildQuery(facts: UnlockRequestFacts): String =
        "ep_id=${facts.epId}" +
            "&cid=${facts.cid}" +
            "&qn=${facts.qn}" +
            "&fnver=${facts.fnver}" +
            "&fnval=${facts.fnval}" +
            "&force_host=${facts.forceHost}" +
            "&fourk=${if (facts.fourk) "1" else "0"}"

    /** 事实 → [RoamingClient.PlayQuery]（网络调用与上面的查询串共用同一映射）。 */
    fun playQuery(facts: UnlockRequestFacts): RoamingClient.PlayQuery =
        RoamingClient.PlayQuery(
            epId = facts.epId,
            cid = facts.cid,
            qn = facts.qn.toLong(),
            fnver = facts.fnver,
            fnval = facts.fnval,
            forceHost = facts.forceHost,
            fourk = facts.fourk,
        )

    /**
     * 网络段：解析 access_key → 逐台漫游取 playurl。
     *
     * access_key 顺序与重构前一致：配置里的服务器令牌优先，为空回落到宿主运行时捕获的
     * [HostAccessKey.lastSeen]（REST 请求发生过就一定有值）；**两者都没有就直接失败**，
     * 不发无鉴权请求。[UnlockConfig.lastArea] 作为优先区（上次成功的区分摊试错）。
     *
     * 签名身份必须跟随服务器区域（th=BstarA，其余=Android）：[RoamingClient] 的 extra
     * 两个分支都恒写 area，硬编码区域会把 th 的 bstar 身份覆盖成 Android（上游 -3）。
     */
    fun roam(
        config: UnlockConfig.Config,
        facts: UnlockRequestFacts,
        module: XposedModule,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): RoamingClient.RoamingResult {
        // 调用方（两个 Hook）已守卫「未启用 / 未配服务器」；这里再兜一层，
        // 免得空服务器列表在下面取 servers[0] 直接抛 IndexOutOfBounds（那会崩在主线程回调上）。
        if (config.servers.isEmpty()) {
            return RoamingClient.RoamingResult(null, null, emptyMap())
        }
        val configAccessKey = config.servers[0].accessKey
        val accessKey = configAccessKey.ifBlank { HostAccessKey.lastSeen() }
        if (accessKey.isNullOrBlank()) {
            HookProbe.first(module, "unlock:noAccessKey", 3) {
                "宿主令牌尚未捕获（REST 请求还没发生过）且未配置 access_key"
            }
            return RoamingClient.RoamingResult(null, null, emptyMap())
        }
        HookProbe.first(module, "unlock:ak", 2) {
            "access_key len=${accessKey.length} " +
                "hex32=${accessKey.length == 32 && accessKey.all { c -> c.isDigit() || c in 'a'..'f' }} " +
                "head4=${accessKey.take(4)}"
        }

        val client = RoamingClient(
            sign = { q, extra -> HostSigner.sign(extra["area"] ?: "cn", q, extra) },
            fetch = { url, mA ->
                // 查询串含账号令牌，不写入日志。
                PlayViewHook.defaultFetch(url, mA, timeoutMs = timeoutMs)
            },
            mobiApp = "android",
        )
        val servers = config.servers.map {
            if (it.accessKey == configAccessKey) it.copy(accessKey = accessKey) else it
        }
        val result = client.fetchPlayUrl(servers, playQuery(facts), priorityArea = UnlockConfig.lastArea())
        if (!result.isSuccess) {
            HookProbe.first(module, "unlock:roamFailed", 5) { result.errors.toString() }
        }
        return result
    }

    /**
     * 解析段：漫游响应体 → DASH 双轨 → CDN host 替换（[UposReplacer]）。
     *
     * 返回 null 表示响应不可用（`unlock:parseFailed` 已发），由适配器决定失败文案。
     *
     * **upos 替换放在这里而不是 [roam]**：替换作用于解析后的 [PlayurlData]，
     * 而 [roam] 的返回值是线上字节；把两者并进一个返回类型只会逼出一个只为传参而存在的
     * 包装类，故按「字节」与「双轨」两段切开。
     */
    fun parsePlayurl(
        config: UnlockConfig.Config,
        content: String,
        module: XposedModule,
    ): PlayurlData? {
        val data0 = PlayurlParser.parse(content)
        if (data0 == null || data0.videos.isEmpty()) {
            HookProbe.first(module, "unlock:parseFailed", 5) { "漫游响应无法解析为 DASH" }
            return null
        }
        // U5 CDN upos 替换（配置了目标 host 才生效；PCDN 形态自动跳过）
        val data = UposReplacer.applyTo(data0, config.uposHost)
        if (data !== data0) {
            HookProbe.first(module, "unlock:uposReplaced", 3) { "host -> ${config.uposHost}" }
        }
        return data
    }

    /**
     * 重建段：宿主原响应字节（可为 null）+ 漫游 DASH → 重建后的宿主响应字节。
     *
     * [hostReplyBytes] 非空时先用宿主类 `parseFrom` 还原成宿主对象，再交给
     * [ResponseReconstructor.rebuildReply]（它走 `newBuilder(reply)` 合并，保留原响应
     * playArc/supplement 里 UI 仍需要的字段）；为空则让它走 `newBuilder()` 从零构造——
     * 「宿主取地址直接抛异常」（下载引擎）走的就是这条。
     *
     * 任何一步失败抛异常，由适配器退化为放行原响应（宁可不解锁，不黑屏）。
     * 返回字节而非宿主对象：K/gRPC 侧拿到字节就能继续走它自己的序列化链。
     */
    fun rebuildReplyBytes(
        cl: ClassLoader,
        hostReplyBytes: ByteArray?,
        data: PlayurlData,
        reqCid: Long,
        reqAid: Long,
    ): ByteArray {
        val hostReply = hostReplyBytes?.let { bytes ->
            cl.loadClass(HostTargets.PLAY_VIEW_UNITE_REPLY_CLASS)
                .getMethod("parseFrom", ByteArray::class.java)
                .invoke(null, bytes)
        }
        val reply = ResponseReconstructor.rebuildReply(cl, hostReply, data, reqCid, reqAid)
        return reply.javaClass.getMethod("toByteArray").invoke(reply) as ByteArray
    }
}
