package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorSegment

object SkipDecision {
    private const val LOOKAHEAD_MS = 250L

    fun findAutoSkipSegment(positionMs: Long, segments: List<SponsorSegment>): SponsorSegment? {
        return segments.firstOrNull { segment ->
            segment.actionType == "skip" &&
                positionMs >= segment.startMs - LOOKAHEAD_MS &&
                positionMs < segment.endMs
        }
    }
}
