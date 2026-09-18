package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorCategories
import com.ctf.bilisb.model.SponsorSegment

object SkipDecision {
    // 对应 APK 命中检测的提前量:进度回调到片段起点前一小段就触发,避免漏跳。
    private const val LOOKAHEAD_MS = 250L

    // 对应 APK `an.d()` = highlight/poi 分类,只标记不自动跳过。
    private val highlightCategory = SponsorCategories.POI_HIGHLIGHT

    /**
     * 找当前位置命中的可跳过片段(自动跳过与手动跳过共用)。
     *
     * 区间语义保持**半开区间**:`[startMs - LOOKAHEAD_MS, endMs)`。
     * 即 position 恰好等于 endMs 时**不再**命中(片段已走完,不该再回跳)。
     *
     * 防御(网络层已经过滤过一遍,这里是最后一道):
     *   - `durationMs <= 0`(时长未知)时不做任何判定,返回 null;
     *   - 片段 `endMs <= startMs`(零长/逆序)直接跳过;
     *   - `positionMs` 先夹到 `[0, durationMs]` 再判定,避免宿主给出越界位置时误命中。
     *
     * @param minDurationMs 最小片段时长。短于此值的片段被过滤掉(避免对 1-2 秒的微小片段
     *                      跳转造成画面抖动);0 表示不过滤。
     */
    fun findActiveSkipSegment(
        positionMs: Long,
        segments: List<SponsorSegment>,
        minDurationMs: Long = 0L,
        durationMs: Long = Long.MAX_VALUE,
    ): SponsorSegment? {
        if (durationMs <= 0L) return null
        val position = positionMs.coerceIn(0L, durationMs)
        return segments.firstOrNull { segment ->
            segment.actionType == "skip" &&
                segment.category != highlightCategory &&
                hasValidRange(segment) &&
                (segment.endMs - segment.startMs) >= minDurationMs &&
                position >= segment.startMs - LOOKAHEAD_MS &&
                position < segment.endMs
        }
    }

    /**
     * 找当前位置命中的、需要静音(actionType=mute)的片段。
     *
     * 与 skip 不同,mute 片段不跳转,只在区间内静音(区间语义同上,半开)。
     * 同样应用 [minDurationMs] 过滤与 [findActiveSkipSegment] 的边界防御。
     */
    fun findActiveMuteSegment(
        positionMs: Long,
        segments: List<SponsorSegment>,
        minDurationMs: Long = 0L,
        durationMs: Long = Long.MAX_VALUE,
    ): SponsorSegment? {
        if (durationMs <= 0L) return null
        val position = positionMs.coerceIn(0L, durationMs)
        return segments.firstOrNull { segment ->
            segment.actionType == "mute" &&
                hasValidRange(segment) &&
                (segment.endMs - segment.startMs) >= minDurationMs &&
                position >= segment.startMs - LOOKAHEAD_MS &&
                position < segment.endMs
        }
    }

    /** 片段区间本身是否可用:起点非负、终点严格大于起点。 */
    private fun hasValidRange(segment: SponsorSegment): Boolean {
        val start = segment.startMs
        val end = segment.endMs
        return start >= 0L && end > start
    }
}
