package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorBlockSubmission
import com.ctf.bilisb.player.PlayerState
import java.util.concurrent.ConcurrentHashMap

/**
 * 提交草稿(两次点按围出片段的起点/终点)。
 *
 * 草稿必须会「过期」:点第一次后用户可能直接去看别的视频、或者隔了很久才点第二次,
 * 那样拼出来的区间没有意义(甚至会把两个不相关的位置当成一个片段提交上去)。
 * 所以每次取出草稿都要经过 [isUsable] 校验,不通过就丢弃并重新开始。
 */
class SubmissionDraftController(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val drafts = ConcurrentHashMap<String, Draft>()

    /**
     * 标记当前位置。第一次调用记下起点并返回 null;第二次调用拼成待提交片段。
     *
     * 返回 null 的三种情况:
     *   1. 这是此视频的第一次标记;
     *   2. 旧草稿已过期 / 不属于当前视频 / 区间不合法 —— 此时已丢弃旧草稿,本次重新记起点;
     *   3. 两次标记落在同一位置(区间长度为 0),零长片段无意义,同样丢弃后重新记起点。
     */
    fun markOrBuildSubmission(
        userId: String,
        state: PlayerState,
        positionMs: Long,
        category: String = "sponsor",
        epId: Int = 0,
    ): SponsorBlockSubmission? {
        val key = videoKey(state)
        val existing = drafts.remove(key)
        val now = nowMs()

        if (existing == null || !isUsable(existing, state, positionMs, now)) {
            // 旧草稿不可用(或本来就没有):丢弃 + 从头开始记起点。
            drafts[key] = newDraft(state, positionMs, category, epId, now)
            return null
        }

        val startMs = minOf(existing.positionMs, positionMs)
        val endMs = maxOf(existing.positionMs, positionMs)
        if (endMs <= startMs) {
            // 同一个位置点了两次:零长片段没有提交价值,丢弃并重新记起点。
            drafts[key] = newDraft(state, positionMs, category, epId, now)
            return null
        }
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
        drafts.remove(videoKey(state))
    }

    /**
     * 按视频清理草稿(切集/换视频时的重置点调用)。
     *
     * 与 [cancel] 的区别:[cancel] 由 UI 用当前 [PlayerState] 触发,而切集时
     * state 已经被新视频覆盖,拿不到旧 key,所以这里直接按调用方记下的 key 清。
     */
    fun clear(videoKey: String) {
        if (videoKey.isEmpty()) return
        drafts.remove(videoKey)
    }

    /** 草稿是否仍然可用:未超时、区间合法、且不越过已知的视频总时长。 */
    private fun isUsable(
        draft: Draft,
        state: PlayerState,
        positionMs: Long,
        now: Long,
    ): Boolean {
        if (draft.bvid != state.bvid || draft.cid != state.cid) return false
        if (now - draft.createdAtMs > DRAFT_TTL_MS) return false

        val startMs = minOf(draft.positionMs, positionMs)
        val endMs = maxOf(draft.positionMs, positionMs)
        if (endMs <= startMs) return false
        // 已知总时长时,终点不允许越界(越界多半说明 state 已被新视频覆盖)。
        if (state.durationMs > 0L && endMs > state.durationMs) return false
        return true
    }

    private fun videoKey(state: PlayerState): String = "${state.bvid}:${state.cid}"

    /** 记一份新草稿,顺带把当时的 bvid/cid 存进去(用于识别「草稿不属于当前视频」)。 */
    private fun newDraft(
        state: PlayerState,
        positionMs: Long,
        category: String,
        epId: Int,
        now: Long,
    ): Draft = Draft(
        positionMs = positionMs,
        category = category,
        epId = epId,
        createdAtMs = now,
        bvid = state.bvid,
        cid = state.cid,
    )

    private data class Draft(
        val positionMs: Long,
        val category: String,
        val epId: Int,
        /** 草稿创建时刻(墙钟),用于 10 分钟过期判定。 */
        val createdAtMs: Long,
        val bvid: String,
        val cid: Long,
    )

    companion object {
        /** 草稿有效期:超过 10 分钟视为用户已经放弃这次标记。 */
        private const val DRAFT_TTL_MS = 10L * 60_000L
    }
}
