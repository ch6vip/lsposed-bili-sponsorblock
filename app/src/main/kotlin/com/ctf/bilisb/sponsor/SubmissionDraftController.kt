package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorBlockSubmission
import com.ctf.bilisb.player.PlayerState
import java.util.concurrent.ConcurrentHashMap

class SubmissionDraftController {
    private val drafts = ConcurrentHashMap<String, Draft>()

    fun markOrBuildSubmission(
        userId: String,
        state: PlayerState,
        positionMs: Long,
        category: String = "sponsor",
        epId: Int = 0,
    ): SponsorBlockSubmission? {
        val key = "${state.bvid}:${state.cid}"
        val existing = drafts.remove(key)
        if (existing == null) {
            drafts[key] = Draft(positionMs, category, epId)
            return null
        }

        val startMs = minOf(existing.positionMs, positionMs)
        val endMs = maxOf(existing.positionMs, positionMs)
        return SponsorBlockSubmission(
            userId = userId,
            bvid = state.bvid,
            cid = state.cid,
            category = existing.category,
            startMs = startMs,
            endMs = endMs,
            videoDurationMs = state.durationMs,
            epId = existing.epId,
        )
    }

    fun cancel(state: PlayerState) {
        drafts.remove("${state.bvid}:${state.cid}")
    }

    private data class Draft(
        val positionMs: Long,
        val category: String,
        val epId: Int,
    )
}
