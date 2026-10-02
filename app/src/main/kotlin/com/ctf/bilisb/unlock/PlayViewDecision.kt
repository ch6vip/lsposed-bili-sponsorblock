package com.ctf.bilisb.unlock

/**
 * `PlayerMoss.playViewUnite` 观测的**受限判定**——纯 JVM 函数，脱离宿主可直接单测。
 *
 * 判定语义与 BiliRoaming 的 needProxyUnite/isThai 分层一致（协议事实，非代码移植）：
 *
 *  1. **THAI_REDIRECT**：请求 cid 非零且与响应 playArc.cid 不一致——泰区等 cid 重定向场景，
 *     必须走代理（判定优先级最高，即使响应缺 PGC supplement 也不按普通 UGC 放过）。
 *  2. **NORMAL_UGC**：UGC 短路——响应可用、supplement 不是 PGC 模型且无重定向，或请求本身
 *     没带 season_id/ep_id（非番剧请求）。解锁不掺和普通视频。注意参考实现的门槛顺序：
 *     UGC 短路发生在番剧请求检查**之前**——番剧请求若响应带 UGC supplement 同样放行。
 *  3. **NORMAL_PGC**：响应带 PGC supplement 且 cid 一致——服务端正常下发了番剧内容。
 *  4. **RESTRICTED**：响应不可用（缺失 / 无 vodInfo），或下载路径上响应形态不标准——
 *     这些是 U1 粒度下无争议的代理候选。受限的**主信号**（PGC supplement 内的
 *     area_limit / preview 弹窗）藏在 supplement 字节里，需 protobuf 管线（U2）后并入。
 *
 * `isDownload` 为 true 时绕过 UGC 短路与「非番剧请求」放行（参考实现同口径：
 * 下载请求即使响应形态不标准也要进入判定，否则缓存入口解不开）。
 */
object PlayViewDecision {

    /** 宿主 Any.supplement 的 typeUrl 常量（dex 字符串池实证）。 */
    const val PGC_ANY_MODEL_TYPE_URL =
        "type.googleapis.com/bilibili.app.playerunite.pgcanymodel.PGCAnyModel"
    const val UGC_ANY_MODEL_TYPE_URL =
        "type.googleapis.com/bilibili.app.playerunite.ugcanymodel.UGCAnyModel"

    enum class Verdict {
        /** 正常下发的番剧内容，不动。 */
        NORMAL_PGC,

        /** 普通视频 / 非番剧请求，解锁不掺和。 */
        NORMAL_UGC,

        /** 番剧请求但响应缺 PGC supplement——区域限制候选。 */
        RESTRICTED,

        /** 请求 cid 与响应 cid 不一致（泰区等重定向），必须走代理。 */
        THAI_REDIRECT,
    }

    /** 一次 playViewUnite 观测中判定所需的全部事实（由 Hook 层反射提取）。 */
    data class Facts(
        val reqVodCid: Long,
        val seasonId: String,
        val epId: String,
        val isDownload: Boolean,
        /** 响应可用：result 存在且带 vodInfo（缺 vodInfo 即受限信号之一）。 */
        val respUsable: Boolean,
        val respPlayArcCid: Long,
        val supplementTypeUrl: String?,
        /** supplement.view_info.dialog.type——国际网关的受限信号藏在这里（"area_limit" 等）。 */
        val supplementDialogType: String = "",
        /** supplement.view_info.end_page.dialog.type——片尾页形态的受限信号（BiliRoaming G0.g 同款）。 */
        val supplementEndPageDialogType: String = "",
        /** supplement.business.is_preview——预览形态（BiliRoaming 同款：预览也走漫游换全量流）。 */
        val isPreview: Boolean = false,
    )

    fun classify(f: Facts): Verdict {
        val isThai = isThai(f)
        if (isThai) return Verdict.THAI_REDIRECT

        // UGC 短路（下载请求不短路）+ 非番剧请求放行——与参考实现的两道放行门同序。
        // 例外：PGC 响应携带受限弹窗时不可放行——国际网关的受限内容返回的是
        // 「可用响应 + 受限弹窗」（2026-10-02 真机实测），不是错误形态。
        // 受限信号三源（BiliRoaming G0.g 还原）：dialog.type=area_limit、
        // end_page.dialog.type 非空、business.is_preview=true。
        val areaLimited = f.supplementDialogType == "area_limit" ||
            f.supplementEndPageDialogType.isNotEmpty() ||
            f.isPreview
        val ugcShortCircuit = !f.isDownload && f.respUsable &&
            f.supplementTypeUrl != PGC_ANY_MODEL_TYPE_URL
        val notPgcRequest = f.seasonId == "0" && f.epId == "0"
        if (!areaLimited && !f.isDownload && (ugcShortCircuit || notPgcRequest)) return Verdict.NORMAL_UGC

        return if (!areaLimited && f.respUsable && f.supplementTypeUrl == PGC_ANY_MODEL_TYPE_URL) {
            Verdict.NORMAL_PGC
        } else {
            Verdict.RESTRICTED
        }
    }

    private fun isThai(f: Facts): Boolean =
        f.reqVodCid != 0L && f.respUsable && f.reqVodCid != f.respPlayArcCid
}
