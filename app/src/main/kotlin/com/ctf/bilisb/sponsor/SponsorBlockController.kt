package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorBlockQuery
import com.ctf.bilisb.player.PlayerActions
import com.ctf.bilisb.player.PlayerHandle
import com.ctf.bilisb.player.PlayerState
import com.ctf.bilisb.ui.PlayerToastBridge
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class SponsorBlockController(
    private val module: XposedModule,
    private val repository: SponsorBlockRepository = SponsorBlockRepository(),
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val requestedVideos = mutableSetOf<String>()
    private val latestStateByContext = ConcurrentHashMap<Int, PlayerState>()
    private val playerHandles = ConcurrentHashMap<Int, PlayerHandle>()
    private val skippedSegments = ConcurrentHashMap.newKeySet<String>()

    fun onPlayerState(state: PlayerState) {
        if (!state.hasVideoId) {
            module.info("skip segment lookup: missing bvid/cid in player state")
            return
        }

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
                "segments fetched video=${query.bvid} cid=${query.cid} " +
                    "status=${result.statusCode} count=${result.segments.size}",
            )
        }
    }

    fun bindContext(contextHash: Int, state: PlayerState) {
        latestStateByContext[contextHash] = state
    }

    fun bindPlayerHandle(handle: PlayerHandle, state: PlayerState) {
        playerHandles[handle.contextHash] = handle
        latestStateByContext[handle.contextHash] = state
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
