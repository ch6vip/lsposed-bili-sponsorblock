package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorSegment

object SkipDecision {
    // 对应 APK 命中检测的提前量:进度回调到片段起点前一小段就触发,避免漏跳。
    private const val LOOKAHEAD_MS = 250L

    // 对应 APK `an.d()` = highlight/poi 分类,只标记不自动跳过。
    private val highlightCategory = "poi_highlight"

    fun findAutoSkipSegment(positionMs: Long, segments: List<SponsorSegment>): SponsorSegment? {
        return segments.firstOrNull { segment ->
            segment.actionType == "skip" &&
                segment.category != highlightCategory &&
                positionMs >= segment.startMs - LOOKAHEAD_MS &&
                positionMs < segment.endMs
        }
    }
}

