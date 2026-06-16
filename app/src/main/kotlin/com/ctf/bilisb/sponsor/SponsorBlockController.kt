package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorBlockQuery
import com.ctf.bilisb.player.PlayerState
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule
import java.util.concurrent.Executors

class SponsorBlockController(
    private val module: XposedModule,
    private val repository: SponsorBlockRepository = SponsorBlockRepository(),
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val requestedVideos = mutableSetOf<String>()

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
}
