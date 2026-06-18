package com.ctf.bilisb.sponsor

import com.ctf.bilisb.model.SponsorBlockQuery
import com.ctf.bilisb.model.SponsorBlockSubmission
import com.ctf.bilisb.model.SponsorBlockConfig
import com.ctf.bilisb.model.SponsorSegment
import com.ctf.bilisb.net.SponsorBlockClient
import com.ctf.bilisb.player.PlayerActions
import com.ctf.bilisb.player.AudioMuteController
import com.ctf.bilisb.player.PlayerBridge
import com.ctf.bilisb.player.PlayerHandle
import com.ctf.bilisb.player.PlayerState
import com.ctf.bilisb.settings.SettingsSnapshot
import com.ctf.bilisb.ui.PlayerToastBridge
import com.ctf.bilisb.ui.ManualSkipButton
import com.ctf.bilisb.ui.SkipCountdownOverlay
import com.ctf.bilisb.util.AidBvidConverter
import com.ctf.bilisb.util.info
import android.content.Context
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SponsorBlockController(
    private val module: XposedModule,
    private val settings: SettingsSnapshot = SettingsSnapshot.DEFAULT,
) {
    private val submissionDraftController = SubmissionDraftController()

    // 用 settings 构造 repository(配置服务器地址和启用类别)
    private val repository = SponsorBlockRepository(
        client = SponsorBlockClient(
            config = SponsorBlockConfig(
                serverAddress = settings.serverAddress,
                enabled = settings.enabled,
                autoSkip = settings.autoSkip,
                enabledCategories = settings.enabledCategories,
            )
        ),
        cacheTtlMs = settings.cacheTtlMs,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    // 正在拉取中的视频 key,防止 onStart 短时间多次触发导致并发重复请求。
    // 缓存有效期由 repository TTL 控制,过期后这里会清掉允许重拉。
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val latestStateByContext = ConcurrentHashMap<Int, PlayerState>()
    private val latestContainerByContext = ConcurrentHashMap<Int, Any>()
    private val playerHandles = ConcurrentHashMap<Int, PlayerHandle>()
    private val skippedSegments = ConcurrentHashMap.newKeySet<String>()
    // 手动模式下当前正在展示跳过按钮的片段 key,用于避免每个进度回调都重设按钮。
    private val manualButtonSegmentKeyByContext = ConcurrentHashMap<Int, String>()
    // 当前正在倒计时的片段 key(自动跳过倒计时模式),用于离开片段时取消。
    private val countdownSegmentKeyByContext = ConcurrentHashMap<Int, String>()

    /**
     * 容器创建时绑定播放器 handle(core 用于 seek,container 用于 toast / context)。
     *
     * 此时还没有 video id —— aid/cid 由 [onVideoIds] 在 video director `onStart`
     * 回调里异步喂入,与 APK `PlayerHookProvider.h/g` 链路一致。
     */
    fun bindPlayerHandle(handle: PlayerHandle) {
        if (closed.get()) return
        playerHandles[handle.contextHash] = handle
        latestContainerByContext[handle.contextHash] = handle.container
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
        if (closed.get()) return
        if (aid <= 0 || cid <= 0) {
            return
        }
        val bvid = AidBvidConverter.aidToBvid(aid)
        // duration 在 director onStart 触发时从 core 取(对应 APK so.d 内部经
        // PlayerHookProvider.o(obj) 取 getDuration)。onStart 早于首帧准备的极端情况下
        // getDuration 可能返回 0,后续进度回调会用进度文本 hook 里的 duration 兜底。
        val core = playerHandles[contextHash]?.core
        val durationMs = core?.let { PlayerActions.durationMs(module, it) } ?: 0L
        val state = PlayerState(
            aid = aid,
            bvid = bvid,
            cid = cid,
            durationMs = durationMs,
            currentPositionMs = core?.let { PlayerActions.currentPositionMs(module, it) } ?: 0L,
        )
        latestStateByContext[contextHash] = state

        val query = SponsorBlockQuery(state.bvid, state.cid)
        // 缓存未过期(repository TTL 内)直接复用,不再发起请求。
        if (repository.getCached(query) != null) {
            return
        }
        val key = "${query.bvid}:${query.cid}"
        // in-flight 防并发:同一视频已在拉取则跳过;拉取完成后不移除 key,
        // 改由缓存命中挡后续请求,TTL 过期清缓存后下次 getCached 返回 null
        // 仍会被 inFlight 拦 —— 因此过期重拉需要显式清 inFlight。
        if (!inFlight.add(key)) {
            return
        }

        executor.execute {
            if (closed.get()) return@execute
            try {
                val result = repository.fetchAndCache(query)
                module.info(
                    "segments fetched video=${query.bvid} aid=$aid cid=${query.cid} " +
                        "status=${result.statusCode} count=${result.segments.size}",
                )
            } finally {
                inFlight.remove(key)
            }
        }
    }

    fun submitSegment(
        contextHash: Int,
        startMs: Long,
        endMs: Long,
        category: String = "sponsor",
        epId: Int = 0,
    ) {
        if (closed.get()) return
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
        if (closed.get()) return
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
        if (closed.get()) return
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
        return PlayerActions.currentPositionMs(module, core)
    }

    private fun submit(submission: SponsorBlockSubmission, state: PlayerState) {
        if (!submission.isValid) {
            module.info("submit skipped: invalid submission $submission")
            return
        }

        executor.execute {
            if (closed.get()) return@execute
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

    fun progressMarkers(contextHash: Int): Pair<Long, List<SponsorSegment>>? {
        if (closed.get()) return null
        val state = latestStateByContext[contextHash] ?: return null
        val segments = repository.getCached(SponsorBlockQuery(state.bvid, state.cid)) ?: return null
        return state.durationMs to segments
    }

    fun segmentsForContext(contextHash: Int): List<SponsorSegment>? {
        if (closed.get()) return null
        val state = latestStateByContext[contextHash] ?: return null
        return repository.getCached(SponsorBlockQuery(state.bvid, state.cid))
    }

    fun latestSegments(contextHash: Int): Pair<Long, List<SponsorSegment>>? {
        if (closed.get()) return null
        val state = latestStateByContext[contextHash] ?: return null
        val segments = repository.getCached(SponsorBlockQuery(state.bvid, state.cid)) ?: return null
        return state.durationMs to segments
    }

    fun onProgress(contextHash: Int, positionMs: Long, durationMs: Long) {
        if (closed.get()) return
        // 手动模式 / 静音模式也需要进度回调,所以这里不再因 autoSkip 关闭而早退,
        // 改在拿到命中片段后按 manualSkip / autoSkip / muteSegments 分流。
        if (!settings.autoSkip && !settings.manualSkip && !settings.muteSegments) {
            return // 所有片段策略都关闭,无需处理
        }

        val state = latestStateByContext[contextHash] ?: return
        // 进度文本 hook 的 duration 比 onStart 时刻更准(首帧已就绪),
        // 持续把非零 duration 回填到 state,供进度条标记与提交使用。
        if (durationMs > 0 && state.durationMs != durationMs) {
            latestStateByContext[contextHash] = state.copy(durationMs = durationMs)
        }
        val handle = playerHandles[contextHash] ?: return
        val query = SponsorBlockQuery(state.bvid, state.cid)
        val segments = repository.getCached(query) ?: return
        // 最小片段时长过滤:短于阈值的片段不跳过、不静音、不显示按钮(避免微小片段抖动)。
        val minDurationMs = (settings.minSkipDurationSec * 1000).toLong()

        // 静音策略(与跳过正交):命中 mute 片段就静音,离开就取消静音。
        if (settings.muteSegments) {
            val muteSeg = SkipDecision.findActiveMuteSegment(positionMs, segments, minDurationMs)
            if (muteSeg != null) {
                AudioMuteController.mute(module, handle.container)
            } else {
                AudioMuteController.unmute(module, handle.container)
            }
        }

        val segment = SkipDecision.findActiveSkipSegment(positionMs, segments, minDurationMs)

        // 手动跳过优先:命中片段时浮出按钮,由用户点按跳过;离开片段时收起。
        if (settings.manualSkip) {
            if (segment == null) {
                if (manualButtonSegmentKeyByContext.remove(contextHash) != null) {
                    ManualSkipButton.hide(module, handle.container)
                }
                return
            }
            val skipKey = "${state.bvid}:${state.cid}:${segment.uuid}:${segment.startMs}-${segment.endMs}"
            if (manualButtonSegmentKeyByContext[contextHash] == skipKey) {
                return // 同一片段已在展示,避免重复重设按钮
            }
            manualButtonSegmentKeyByContext[contextHash] = skipKey
            val categoryName = getCategoryDisplayName(segment.category)
            ManualSkipButton.show(module, handle.container, categoryName) {
                PlayerActions.seekTo(module, handle.core, segment.endMs)
                manualButtonSegmentKeyByContext.remove(contextHash)
                SkipStatsStore.record(segment.category, segment.endMs - segment.startMs)
                if (settings.showToast) {
                    val durationSec = (segment.endMs - segment.startMs) / 1000.0
                    PlayerToastBridge.showSkipToast(
                        module, handle.container, String.format("%s (%.1f秒)", categoryName, durationSec),
                    )
                }
            }
            module.info(
                "manual skip button shown video=${state.bvid} cid=${state.cid} " +
                    "segment=${segment.startMs}-${segment.endMs} category=${segment.category}",
            )
            return
        }

        // 以下为自动跳过(立即 / 倒计时)。autoSkip 关闭则只剩静音生效,直接返回。
        if (!settings.autoSkip) {
            return
        }

        if (segment == null) {
            // 离开片段:取消尚在进行的倒计时浮层。
            if (countdownSegmentKeyByContext.remove(contextHash) != null) {
                SkipCountdownOverlay.cancel(module, handle.container)
            }
            return
        }
        val skipKey = "${state.bvid}:${state.cid}:${segment.uuid}:${segment.startMs}-${segment.endMs}"

        // 倒计时模式:进入片段先显示"N秒后跳过 [取消]",倒计时结束才跳。
        if (settings.skipCountdownSec > 0) {
            // skippedSegments 去重:倒计时启动即标记,避免每个进度回调重复启动;
            // 用户取消后保留标记(本片段不再触发),自然结束时也已标记。
            if (!skippedSegments.add(skipKey)) {
                return
            }
            countdownSegmentKeyByContext[contextHash] = skipKey
            val categoryName = getCategoryDisplayName(segment.category)
            val endMs = segment.endMs
            val startMs = segment.startMs
            SkipCountdownOverlay.start(
                module = module,
                host = handle.container,
                label = categoryName,
                totalMs = (settings.skipCountdownSec * 1000).toLong(),
                onComplete = {
                    countdownSegmentKeyByContext.remove(contextHash)
                    PlayerActions.seekTo(module, handle.core, endMs)
                    SkipStatsStore.record(segment.category, endMs - startMs)
                    if (settings.showToast) {
                        val durationSec = (endMs - startMs) / 1000.0
                        PlayerToastBridge.showSkipToast(
                            module, handle.container, String.format("%s (%.1f秒)", categoryName, durationSec),
                        )
                    }
                },
                onCancel = {
                    countdownSegmentKeyByContext.remove(contextHash)
                },
            )
            module.info(
                "auto-skip countdown started video=${state.bvid} cid=${state.cid} " +
                    "segment=$startMs-$endMs category=${segment.category} countdown=${settings.skipCountdownSec}s",
            )
            return
        }

        // 立即自动跳过。
        if (!skippedSegments.add(skipKey)) {
            return
        }

        // APK behavior uses PlayerHookProvider.z(playerCore, endMs, true).
        // This is the direct Hook equivalent, with the handle coming from the
        // player container/context binding.
        PlayerActions.seekTo(module, handle.core, segment.endMs)
        SkipStatsStore.record(segment.category, segment.endMs - segment.startMs)
        if (settings.showToast) {
            val categoryName = getCategoryDisplayName(segment.category)
            val durationSec = (segment.endMs - segment.startMs) / 1000.0
            val message = String.format("%s (%.1f秒)", categoryName, durationSec)
            PlayerToastBridge.showSkipToast(module, handle.container, message)
        }
        module.info(
            "auto-skipped video=${state.bvid} cid=${state.cid} " +
                "position=$positionMs duration=$durationMs " +
                "segment=${segment.startMs}-${segment.endMs} category=${segment.category}",
        )
    }

    /** 播放器销毁时调用:确保静音被取消,避免静音状态泄漏到其它媒体。 */
    fun onPlayerDestroyed(host: Any) {
        AudioMuteController.unmute(module, host)
        ManualSkipButton.hide(module, host)
        SkipCountdownOverlay.cancel(module, host)

        val contextHash = PlayerBridge.contextHash(host)
        if (contextHash != 0) {
            removeContext(contextHash)
        } else {
            val staleKeys = latestContainerByContext
                .filterValues { it === host }
                .keys
                .toList()
            for (key in staleKeys) {
                removeContext(key)
            }
        }

        if (latestStateByContext.isEmpty()) {
            close()
        }
    }

    private fun removeContext(contextHash: Int) {
        latestStateByContext.remove(contextHash)
        latestContainerByContext.remove(contextHash)
        playerHandles.remove(contextHash)
        manualButtonSegmentKeyByContext.remove(contextHash)
        countdownSegmentKeyByContext.remove(contextHash)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        inFlight.clear()
        latestStateByContext.clear()
        latestContainerByContext.clear()
        playerHandles.clear()
        skippedSegments.clear()
        manualButtonSegmentKeyByContext.clear()
        countdownSegmentKeyByContext.clear()
        executor.shutdownNow()
    }

    private fun getCategoryDisplayName(category: String): String =
        com.ctf.bilisb.model.SponsorCategories.displayName(category)
}
