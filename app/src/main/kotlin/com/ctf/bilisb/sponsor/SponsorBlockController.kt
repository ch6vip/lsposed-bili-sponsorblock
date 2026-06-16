package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorBlockQuery
import com.ctf.bilisb.model.SponsorBlockSubmission
import com.ctf.bilisb.model.SponsorSegment
import com.ctf.bilisb.player.PlayerActions
import com.ctf.bilisb.player.PlayerBridge
import com.ctf.bilisb.player.PlayerHandle
import com.ctf.bilisb.player.PlayerState
import com.ctf.bilisb.ui.PlayerToastBridge
import com.ctf.bilisb.util.AidBvidConverter
import com.ctf.bilisb.util.info
import android.content.Context
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class SponsorBlockController(
    private val module: XposedModule,
    private val repository: SponsorBlockRepository = SponsorBlockRepository(),
    private val submissionDraftController: SubmissionDraftController = SubmissionDraftController(),
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val requestedVideos = mutableSetOf<String>()
    private val latestStateByContext = ConcurrentHashMap<Int, PlayerState>()
    private val latestContainerByContext = ConcurrentHashMap<Int, Any>()
    private val playerHandles = ConcurrentHashMap<Int, PlayerHandle>()
    private val skippedSegments = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var latestContextHash: Int = 0

    /**
     * 容器创建时绑定播放器 handle(core 用于 seek,container 用于 toast / context)。
     *
     * 此时还没有 video id —— aid/cid 由 [onVideoIds] 在 video director `onStart`
     * 回调里异步喂入,与 APK `PlayerHookProvider.h/g` 链路一致。
     */
    fun bindPlayerHandle(handle: PlayerHandle) {
        playerHandles[handle.contextHash] = handle
        latestContainerByContext[handle.contextHash] = handle.container
        latestContextHash = handle.contextHash
        module.info("player handle bound context=${handle.contextHash}")
    }

    /**
     * video director `onStart(aid, cid)` 回调入口。
     *
     * 对应 APK `so.d(aid, cid)` → `SponsorBlockPatch.c(aid, cid, epId, duration, ...)`:
     * aid 实时转 bvid(对应 APK `i6.H(aid)`),拼成 query 后异步拉片段。
     * 切集时同一 context 会再次回调,按 `bvid:cid` 去重只拉新视频。
     */
    fun onVideoIds(contextHash: Int, aid: Long, cid: Long) {
        if (aid <= 0 || cid <= 0) {
            return
        }
        val bvid = AidBvidConverter.aidToBvid(aid)
        val state = PlayerState(
            aid = aid,
            bvid = bvid,
            cid = cid,
            durationMs = 0L,
            currentPositionMs = 0L,
        )
        latestStateByContext[contextHash] = state
        latestContextHash = contextHash

        val query = SponsorBlockQuery(state.bvid, state.cid)
        val key = "${query.bvid}:${query.cid}"
        synchronized(requestedVideos) {
            if (!requestedVideos.add(key)) {
                return
            }
        }

        executor.execute {
            val result = repository.fetchAndCache(query)
            module.info(
                "segments fetched video=${query.bvid} aid=$aid cid=${query.cid} " +
                    "status=${result.statusCode} count=${result.segments.size}",
            )
        }
    }

    fun bindContext(contextHash: Int, state: PlayerState) {
        latestStateByContext[contextHash] = state
        latestContextHash = contextHash
    }

    fun submitSegment(
        contextHash: Int,
        startMs: Long,
        endMs: Long,
        category: String = "sponsor",
        epId: Int = 0,
    ) {
        val state = latestStateByContext[contextHash] ?: run {
            module.info("submit skipped: missing player state for context=$contextHash")
            return
        }
        val userId = userIdForContext(contextHash) ?: return
        val submission = SponsorBlockSubmission(
            userId = userId,
            bvid = state.bvid,
            cid = state.cid,
            category = category,
            startMs = startMs,
            endMs = endMs,
            videoDurationMs = state.durationMs,
            epId = epId,
        )
        submit(submission, state)
    }

    fun markOrSubmitCurrentPosition(
        contextHash: Int,
        category: String = "sponsor",
        epId: Int = 0,
    ) {
        val state = latestStateByContext[contextHash] ?: run {
            module.info("mark skipped: missing player state for context=$contextHash")
            return
        }
        val userId = userIdForContext(contextHash) ?: return
        val positionMs = currentPositionMs(contextHash) ?: state.currentPositionMs
        val submission = submissionDraftController.markOrBuildSubmission(
            userId = userId,
            state = state,
            positionMs = positionMs,
            category = category,
            epId = epId,
        )
        if (submission == null) {
            module.info(
                "segment mark start video=${state.bvid} cid=${state.cid} " +
                    "position=$positionMs category=$category",
            )
            return
        }
        submit(submission, state)
    }

    fun cancelSubmissionDraft(contextHash: Int) {
        val state = latestStateByContext[contextHash] ?: return
        submissionDraftController.cancel(state)
        module.info("segment draft canceled video=${state.bvid} cid=${state.cid}")
    }

    private fun userIdForContext(contextHash: Int): String? {
        val context = latestContainerByContext[contextHash]?.let { PlayerBridge.context(it) as? Context } ?: run {
            module.info("submit skipped: missing android context for context=$contextHash")
            return null
        }
        return UserIdentityStore(context).getOrCreateUserId()
    }

    private fun currentPositionMs(contextHash: Int): Long? {
        val core = playerHandles[contextHash]?.core ?: return null
        return runCatching {
            val method = core.javaClass.getDeclaredMethod("getCurrentPosition").apply {
                isAccessible = true
            }
            (method.invoke(core) as? Number)?.toLong()
        }.getOrNull()
    }

    private fun submit(submission: SponsorBlockSubmission, state: PlayerState) {
        if (!submission.isValid) {
            module.info("submit skipped: invalid submission $submission")
            return
        }

        executor.execute {
            val result = repository.submit(submission)
            module.info(
                "segment submitted video=${state.bvid} cid=${state.cid} " +
                    "status=${result.statusCode} start=${submission.startMs} end=${submission.endMs} " +
                    "category=${submission.category}",
            )
            if (result.statusCode == 200) {
                repository.clear(SponsorBlockQuery(state.bvid, state.cid))
            }
        }
    }

    fun progressMarkers(): Pair<Long, List<SponsorSegment>>? {
        val state = latestStateByContext[latestContextHash] ?: return null
        val segments = repository.getCached(SponsorBlockQuery(state.bvid, state.cid)) ?: return null
        return state.durationMs to segments
    }

    fun segmentsForContext(contextHash: Int): List<SponsorSegment>? {
        val state = latestStateByContext[contextHash] ?: return null
        return repository.getCached(SponsorBlockQuery(state.bvid, state.cid))
    }

    fun onProgress(contextHash: Int, positionMs: Long, durationMs: Long) {
        val state = latestStateByContext[contextHash] ?: return
        val handle = playerHandles[contextHash] ?: return
        val query = SponsorBlockQuery(state.bvid, state.cid)
        val segments = repository.getCached(query) ?: return
        val segment = SkipDecision.findAutoSkipSegment(positionMs, segments) ?: return
        val skipKey = "${state.bvid}:${state.cid}:${segment.uuid}:${segment.startMs}-${segment.endMs}"
        if (!skippedSegments.add(skipKey)) {
            return
        }

        // APK behavior uses PlayerHookProvider.z(playerCore, endMs, true).
        // This is the direct Hook equivalent, with the handle coming from the
        // player container/context binding.
        PlayerActions.seekTo(module, handle.core, segment.endMs)
        PlayerToastBridge.showSkipToast(module, handle.container, "已跳过 ${segment.category}")
        module.info(
            "auto-skipped video=${state.bvid} cid=${state.cid} " +
                "position=$positionMs duration=$durationMs " +
                "segment=${segment.startMs}-${segment.endMs} category=${segment.category}",
        )
    }
}
