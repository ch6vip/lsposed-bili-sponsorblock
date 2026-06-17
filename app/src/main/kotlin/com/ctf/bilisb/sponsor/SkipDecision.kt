package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorSegment

object SkipDecision {
    // 对应 APK 命中检测的提前量:进度回调到片段起点前一小段就触发,避免漏跳。
    private const val LOOKAHEAD_MS = 250L

    // 对应 APK `an.d()` = highlight/poi 分类,只标记不自动跳过。
    private val highlightCategory = "poi_highlight"

    /**
     * 找当前位置命中的可跳过片段(自动跳过与手动跳过共用)。
     *
     * @param minDurationMs 最小片段时长。短于此值的片段被过滤掉(避免对 1-2 秒的微小片段
     *                      跳转造成画面抖动);0 表示不过滤。
     */
    fun findActiveSkipSegment(
        positionMs: Long,
        segments: List<SponsorSegment>,
        minDurationMs: Long = 0L,
    ): SponsorSegment? {
        return segments.firstOrNull { segment ->
            segment.actionType == "skip" &&
                segment.category != highlightCategory &&
                (segment.endMs - segment.startMs) >= minDurationMs &&
                positionMs >= segment.startMs - LOOKAHEAD_MS &&
                positionMs < segment.endMs
        }
    }
}

