package com.ctf.bilisb

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HookResolve
import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.player.PlayerBridge
import com.ctf.bilisb.player.PlayerHandle
import com.ctf.bilisb.player.VideoDirectorListener
import com.ctf.bilisb.sponsor.SponsorBlockController
import com.ctf.bilisb.model.SponsorCategories
import com.ctf.bilisb.settings.SettingsCodec
import com.ctf.bilisb.settings.SettingsKeys
import com.ctf.bilisb.settings.SettingsWriter
import com.ctf.bilisb.sponsor.SkipStatsStore
import com.ctf.bilisb.ui.Callbacks
import com.ctf.bilisb.ui.ManualSegmentItem
import com.ctf.bilisb.ui.PlayerSheetState
import com.ctf.bilisb.ui.ProgressMarkerPainter
import com.ctf.bilisb.ui.SheetStateFormatter
import com.ctf.bilisb.ui.ValueEditingCallbacks
import com.ctf.bilisb.ui.SponsorBlockPlayerSheet
import com.ctf.bilisb.ui.RemainingTimeFormatter
import com.ctf.bilisb.util.info
import com.ctf.bilisb.util.warn
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Hook 安装与编排中枢。
 *
 * 目标是 **bilibili 6.5.0（`com.bilibili.app.in`）**；类名/方法名一律走 [HostTargets] 候选表，
 * 解析结果由 [HookProbe] 记录（哪条 hook 装上、哪条没找到），避免宿主改版后静默失效。
 *
 * 6.5.0 相对旧目标（8.96）的关键差异（见 docs/APK_6.5.0_ANALYSIS.md）：
 *   1. 播放器容器入口从「Hook 容器 `be1.j` 生命周期」改成「Hook widget 的 `bindPlayerContainer(f)`」；
 *   2. 进度回调方法名被混淆成 `G(int,int)`（旧目标 `onPlayerProgressChange`）；
 *   3. aid/cid 走 `PlayDirectorServiceV3#j0(E0)` 注册观察者 + `Video$e#z()` -> `Video$a`；
 *   4. 进度条绘制目标从 `seek.v3.q/e` 换成 `seek.v3.g`（`seek.v3.a` 是热度曲线，不能挂）。
 */
object BiliSponsorBlockHooks {
    private val installed = ConcurrentHashMap.newKeySet<String>()

    // 这两个字段被多个线程读（进度回调线程 / draw 线程 / 容器绑定线程），必须 @Volatile，
    // 否则设置改动或 controller 重建后，其它线程可能长时间读到旧值。
    @Volatile
    private var sponsorBlockController: SponsorBlockController? = null

    @Volatile
    private var settings: com.ctf.bilisb.settings.SettingsSnapshot = com.ctf.bilisb.settings.SettingsSnapshot.DEFAULT

    fun install(module: XposedModule, param: PackageLoadedParam, processName: String) {
        val installKey = "${param.packageName}:$processName"
        if (!installed.add(installKey)) {
            return
        }

        val cl = param.defaultClassLoader
        module.info("Installing hooks for ${param.packageName} process=$processName with $cl")

        // aid/cid 消费方：观察者回调 -> controller
        VideoDirectorListener.setVideoIdSink { contextHash, aid, cid ->
            sponsorBlockController?.onVideoIds(contextHash, aid, cid)
        }

        // 注意:此时 Application 尚未创建,无法获取 Context 读取设置。
        // 设置加载延迟到容器绑定回调里,那时能拿到 Context 进行 ContentProvider IPC。
        //
        // 每条安装点单独兜底：某一类找不到/不可 hook 时，不能连带把后面的功能全部丢掉
        // （曾经出现过"一条 hook 抛异常 → 标记/时间扣减/我的页菜单全都没装"的情况）。
        installSafely(module, "directorService") { hookDirectorService(module, cl) }
        installSafely(module, "containerBinding") { hookContainerBinding(module, cl) }
        installSafely(module, "legacyContainer") { hookLegacyContainer(module, cl) }
        installSafely(module, "seekTrack") { hookProgressDrawable(module, cl) }
        installSafely(module, "progressCallback") { hookProgressText(module, cl) }
        installSafely(module, "mineMenu") { com.ctf.bilisb.hook.MineMenuInjector.install(module, cl) }
        installSafely(module, "morePanel") {
            com.ctf.bilisb.hook.MorePanelInjector.install(module, cl) { rowView ->
                openPlayerSheet(module, rowView)
            }
        }

        module.info(HookProbe.summary())
    }

    private inline fun installSafely(module: XposedModule, key: String, block: () -> Unit) {
        runCatching { block() }.onFailure { throwable ->
            HookProbe.miss(module, key, "install threw: ${throwable.javaClass.simpleName}: ${throwable.message}")
        }
    }

    // ------------------------------------------------------------------ aid/cid 入口

    /**
     * 6.5.0：`PlayDirectorServiceV3#j0(E0)` 是观察者注册入口。
     * Hook 它是为了拿到服务实例，然后把我们自己的 `E0` 代理也注册进去。
     */
    private fun hookDirectorService(module: XposedModule, cl: ClassLoader) {
        for (className in HostTargets.DIRECTOR_SERVICE_CLASSES) {
            val clazz = runCatching { Class.forName(className, false, cl) }.getOrNull() ?: continue
            // 用「参数类型名」而不是只看参数个数：混淆名 j0 极易撞名，命中错误重载会静默用错语义
            val addMethod = HookResolve.declaredMethodByShape(
                clazz,
                HostTargets.DIRECTOR_ADD_OBSERVER_METHODS,
                1,
                paramTypeName = { it == HostTargets.DIRECTOR_OBSERVER_INTERFACE || it == HostTargets.LEGACY_OBSERVER_INTERFACE },
            ) ?: continue

            module.hook(addMethod)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        VideoDirectorListener.registerDirectorService(module, chain.getThisObject())
                    }
                    result
                }
            HookProbe.ok(module, "directorService", "$className#${addMethod.name}")
            return
        }
        HookProbe.miss(module, "directorService", HostTargets.DIRECTOR_SERVICE_CLASSES.joinToString())
    }

    // ------------------------------------------------------------------ 播放器容器绑定

    /**
     * 6.5.0：widget 在拿到播放器容器时回调 `bindPlayerContainer(tv.danmaku.biliplayerv2.f)`。
     * 这是 6.5.0 上最稳的「进入播放页」入口（旧目标是容器自己的生命周期方法）。
     *
     * 同时在同一个 widget 上挂 `onDetachedFromWindow` 作为**播放器离开**信号 —— 6.5.0 没有
     * 旧目标 `be1.j#onDestroy` 那种容器生命周期方法，没有这个信号就会出现：
     * 静音不解除、倒计时离开了还在跑（到点对已废弃的 core seek 并记统计）、按钮/浮层残留、
     * controller 里按 contextHash 的容器强引用永不释放。
     */
    private fun hookContainerBinding(module: XposedModule, cl: ClassLoader) {
        var hooked = false
        var teardownHooked = false
        for (className in HostTargets.CONTAINER_BINDING_CLASSES) {
            val clazz = runCatching { Class.forName(className, false, cl) }.getOrNull() ?: continue
            val method = HookResolve.declaredMethodByShape(
                clazz,
                listOf(HostTargets.BIND_CONTAINER_METHOD),
                1,
                paramTypeName = { it == HostTargets.CONTAINER_INTERFACE },
            ) ?: continue

            hookAfter(module, method, "containerBinding:$className") { chain ->
                val host = chain.getThisObject() ?: return@hookAfter
                val container = chain.getArgs().getOrNull(0)
                // 探针：先确认这个入口到底有没有被调用（宿主类存在 ≠ 这条路径被走到）
                HookProbe.first(module, "bindPlayerContainerCalled", 5) {
                    "${host.javaClass.name} <- ${container?.javaClass?.name ?: "null"}"
                }
                bindPlayer(module, host, container)
            }
            hooked = true

            // 对每个成功挂上 bind 的 widget 类都尝试挂 detach:
            // 两个 widget 类(PlayerSeekWidget3 / PlayerProgressTextWidget)的实例各自独立
            // detach,只挂第一个会让第二个类的 widget 分离时不触发清理。onPlayerLeft 幂等,
            // 重复触发只是多打一条探针。
            HookResolve.declaredMethod(clazz, listOf(HostTargets.WIDGET_DETACH_METHOD))?.let { detach ->
                hookAfter(module, detach, "playerTeardown:$className") { chain ->
                    val host = chain.getThisObject() ?: return@hookAfter
                    onPlayerLeft(module, host)
                }
                teardownHooked = true
            }
        }
        if (!hooked) {
            HookProbe.miss(module, "containerBinding", HostTargets.CONTAINER_BINDING_CLASSES.joinToString())
        }
        if (!teardownHooked) {
            HookProbe.miss(module, "playerTeardown", "widget detach hook not found")
        }
    }

    /**
     * 播放器离开/销毁：取消静音、隐藏浮层与按钮、释放 contextHash 关联状态。
     *
     * 这是 6.5.0 上真正会被调用的清理入口（挂在 widget 的 `onDetachedFromWindow` 上）。
     */
    private fun onPlayerLeft(module: XposedModule, host: Any) {
        HookProbe.first(module, "playerTeardownCalled", 5) { host.javaClass.name }
        pendingBindRef.set(null)
        VideoDirectorListener.unregister(host)
        sponsorBlockController?.onPlayerDestroyed(host)
        module.info("player left: teardown done host=${host.javaClass.name}")
    }

    /**
     * 旧目标（8.96）的容器生命周期入口，保留作为兜底：
     * 这些类在 6.5.0 不存在，探针会记录 MISS。
     */
    private fun hookLegacyContainer(module: XposedModule, cl: ClassLoader) {
        val clazz = runCatching {
            Class.forName(HostTargets.LEGACY_CONTAINER_CLASS, false, cl)
        }.getOrNull()
        if (clazz == null) {
            HookProbe.skip(module, "legacyContainer", "not present in this host")
            return
        }

        HookResolve.declaredMethod(clazz, listOf("onCreate"), Bundle::class.java)?.let { method ->
            hookAfter(module, method, "legacyContainer:onCreate") { chain ->
                val container = chain.getThisObject() ?: return@hookAfter
                bindPlayer(module, container, container)
            }
        }
        HookResolve.declaredMethod(clazz, listOf("onStart"))?.let { method ->
            hookAfter(module, method, "legacyContainer:onStart") { chain ->
                val container = chain.getThisObject() ?: return@hookAfter
                VideoDirectorListener.noteContextHash(PlayerBridge.contextHash(container))
                VideoDirectorListener.tryRegisterFromHost(module, container)
            }
        }
        HookResolve.declaredMethod(clazz, listOf("onDestroy"))?.let { method ->
            hookAfter(module, method, "legacyContainer:onDestroy") { chain ->
                val container = chain.getThisObject() ?: return@hookAfter
                VideoDirectorListener.unregister(container)
                sponsorBlockController?.onPlayerDestroyed(container)
            }
        }
    }

    /**
     * 待补绑的播放器：`bindPlayerContainer` 触发时 core 往往还没注入，
     * 这时先记下来，等第一次进度回调（那时 widget 已经有 core）再补绑。
     */
    private data class PendingBind(val contextHash: Int, val container: Any, val host: Any)

    /** 补绑用的挂起绑定。AtomicReference 抢占式清空,避免多线程重复 completeBind。 */
    private val pendingBindRef = java.util.concurrent.atomic.AtomicReference<PendingBind?>()

    /** 最近一次绑定的 contextHash，供日志与状态组装使用。 */
    @Volatile
    private var lastBoundContextHash: Int = 0

    /**
     * 面板里改设置用的写入器（宿主进程内长期持有）。
     *
     * 必须长期持有：SettingsWriter 把变更监听注册在 prefs 上，writer 被回收后监听会失效。
     */
    @Volatile
    private var settingsWriter: SettingsWriter? = null

    /**
     * 绑定播放器：取 Context / core，交给 controller，并挂提交按钮。
     *
     * @param host 触发绑定的对象（6.5.0 是 widget，本身是 View，用于挂播放器内 UI）
     * @param container 播放器容器（6.5.0 是 `tv.danmaku.biliplayerv2.f`）
     */
    private fun bindPlayer(module: XposedModule, host: Any, container: Any?) {
        val context = container?.let { PlayerBridge.context(it) }
            ?: PlayerBridge.context(host)
            ?: run {
                HookProbe.first(module, "bindPlayerNoContext", 3) { host.javaClass.name }
                return
            }

        ensureSettingsLoaded(module, context)

        if (!settings.enabled) {
            module.info("player bound but SponsorBlock disabled in settings")
            return
        }

        val contextHash = PlayerBridge.contextHash(context)
        VideoDirectorListener.noteContextHash(contextHash)
        // 之前回调里拿到的 aid/cid 可能因为"还没有容器"被缓存下来，这里补发
        VideoDirectorListener.flushPendingIds(module, contextHash)

        // core 三个来源：widget 自己 -> 容器 -> director 服务（真机实测 bind 时 widget 的 core 还是 null）
        val core = PlayerBridge.coreService(host)
            ?: container?.let { PlayerBridge.coreService(it) }
            ?: PlayerBridge.coreServiceFromDirector(VideoDirectorListener.lastDirectorService())

        if (core == null) {
            pendingBindRef.set(PendingBind(contextHash, container ?: host, host))
            module.info("core not ready at bind time, defer binding context=$contextHash host=${host.javaClass.name}")
            return
        }

        completeBind(module, contextHash, container ?: host, host, core)
    }

    /** 首次进度回调时补绑（那时 widget 已完成服务注入）。
     *  用 AtomicReference 抢占式清空，避免多条回调并发时对同一 pending 重复 completeBind。 */
    private fun ensureDeferredBind(module: XposedModule, progressWidget: Any) {
        val pending = pendingBindRef.getAndSet(null) ?: return
        // 必须校验「补绑用的 widget」就是当初发起绑定的那个播放器：
        // 否则会用 A 的 contextHash/container 配 B 的 core（小窗/快速切集时串台）。
        if (pending.host !== progressWidget &&
            PlayerBridge.contextHash(progressWidget) != pending.contextHash
        ) {
            // 校验失败:把 pending 放回去,等待真正匹配的 widget
            pendingBindRef.compareAndSet(null, pending)
            return
        }
        val core = PlayerBridge.coreService(progressWidget)
            ?: PlayerBridge.coreServiceFromDirector(VideoDirectorListener.lastDirectorService())
            ?: run {
                // core 还没就绪:放回 pending,等下一次回调
                pendingBindRef.compareAndSet(null, pending)
                return
            }
        completeBind(module, pending.contextHash, pending.container, pending.host, core)
    }

    private fun completeBind(
        module: XposedModule,
        contextHash: Int,
        container: Any,
        host: Any,
        core: Any,
    ) {
        sponsorBlockController?.bindPlayerHandle(PlayerHandle(contextHash, container, core))

        // 播放器内的「SB」提交按钮已按需求移除（不再注入任何播放器内 UI）。
        // 提交相关的代码（客户端的 POST 提交、草稿控制器、controller.markOrSubmitCurrentPosition）
        // 仍然保留，等以后有别的入口（例如设置页/长按菜单）时可以直接复用。

        // 探针：从 widget 上试取 director 服务（6.5.0 只有部分 widget 暴露）
        VideoDirectorListener.tryRegisterFromHost(module, host)

        lastBoundContextHash = contextHash

        module.info("player bound context=$contextHash host=${host.javaClass.name} core=${core.javaClass.name}")
    }

    private fun ensureSettingsLoaded(module: XposedModule, containerContext: android.content.Context) {
        val freshSettings = runCatching {
            com.ctf.bilisb.settings.ModuleSettings.reload(module, containerContext)
        }.getOrElse {
            module.info("ModuleSettings reload failed, using defaults: ${it.message}")
            com.ctf.bilisb.settings.SettingsSnapshot.DEFAULT
        }

        // 注意：必须先拿旧快照再赋值。`settings` 是同一个字段，若先赋值再比较，
        // `settings != freshSettings` 恒为 false（data class equals 自反），
        // 会导致除总开关外的所有设置改动都不生效（只有重建 controller 才会带上新配置）。
        module.info("Settings snapshot on player enter: $freshSettings")
        applySnapshot(module, freshSettings, source = "player enter")
    }

    /**
     * 应用一份设置快照，必要时重建 controller。
     *
     * 抽成独立函数是为了让「播放器面板里改开关」立即生效：那条路径直接用宿主 prefs
     * 生成快照后调用这里，不必再走一次可能读到旧镜像的 IPC/文件回读。
     */
    private fun applySnapshot(
        module: XposedModule,
        freshSettings: com.ctf.bilisb.settings.SettingsSnapshot,
        source: String,
    ) {
        val previousSettings = settings
        settings = freshSettings

        if (!freshSettings.enabled) {
            sponsorBlockController?.close()
            sponsorBlockController = null
            module.info("SponsorBlock disabled in settings ($source)")
            return
        }

        val currentController = sponsorBlockController
        if (currentController == null || previousSettings != freshSettings) {
            currentController?.close()
            sponsorBlockController = SponsorBlockController(module, freshSettings)
            module.info("SponsorBlock controller initialized settingsChanged=${currentController != null} ($source)")
        } else {
            module.info("SponsorBlock controller reused ($source)")
        }
    }

    // ------------------------------------------------------------------ 播放器面板（空降助手）

    /** 组装面板状态并展示；返回是否成功展示。 */
    fun openPlayerSheet(module: XposedModule, host: Any): Boolean {
        val activity = PlayerBridge.activity(host) ?: run {
            HookProbe.first(module, "sheetNoActivity", 3) { host.javaClass.name }
            return false
        }
        val context = PlayerBridge.context(host) ?: return false
        val controller = sponsorBlockController ?: return false

        // 面板入口可能来自宿主「更多」面板里我们自己那一行：它的 Context 是 Dialog 的
        // ContextThemeWrapper（hash 与播放器容器的 Context 不同），所以这里优先选
        // 「controller 真的有状态」的那个 contextHash，取不到再回落到最近绑定的那个。
        val hostHash = PlayerBridge.contextHash(context).takeIf { it != 0 }
        val contextHash = listOfNotNull(hostHash, lastBoundContextHash.takeIf { it != 0 })
            .firstOrNull { controller.sheetSnapshot(it) != null }
            ?: lastBoundContextHash.takeIf { it != 0 }
            ?: return false
        HookProbe.first(module, "sheetContextHash", 3) { "host=$hostHash used=$contextHash" }

        val snapshot = controller.sheetSnapshot(contextHash)
        val inside = snapshot?.currentSegment
        val stats = SkipStatsStore.snapshot()

        val manualItems = snapshot?.segments.orEmpty().map { segment ->
            ManualSegmentItem(
                label = SheetStateFormatter.formatManualSegmentItem(
                    SponsorCategories.displayName(segment.category),
                    segment.startMs,
                    segment.endMs,
                ),
                startMs = segment.startMs,
                endMs = segment.endMs,
            )
        }

        val state = PlayerSheetState(
            segmentCount = snapshot?.segmentCount ?: 0,
            playheadInsideSegment = inside != null,
            insideSegmentLabel = inside?.let {
                "${SponsorCategories.displayName(it.category)} " +
                    "${SheetStateFormatter.formatSeconds(it.startMs)}-${SheetStateFormatter.formatSeconds(it.endMs)}"
            },
            autoSkipEnabled = settings.autoSkip,
            submitHint = "标记并提交跳过段",
            manualSkipSummary = SheetStateFormatter.formatManualSummary(snapshot?.segmentCount ?: 0),
            manualSegments = manualItems,
            serviceStatus = SheetStateFormatter.formatServiceStatus(
                ok = true,
                skippedCount = stats.totalCount.toInt(),
                savedSeconds = stats.totalDurationMs / 1000,
            ),
            showToast = settings.showToast,
            showSeekbarMarker = settings.showSeekbarMarker,
            showSkipStats = settings.showSkipStats,
            minSkipDurationLabel = SheetStateFormatter.formatSeconds((settings.minSkipDurationSec * 1000).toLong()),
            userIdLabel = settings.userId,
        )

        val callbacks = object : Callbacks, ValueEditingCallbacks {
            override fun onToggleAutoSkip(enabled: Boolean) =
                updateSetting(module, context, SettingsKeys.AUTO_SKIP, enabled)

            override fun onSubmitSegment() {
                sponsorBlockController?.markOrSubmitCurrentPosition(contextHash, settings.defaultSubmitCategory)
                module.info("playerSheet: submit toggled context=$contextHash")
            }

            override fun onManualSkip(item: ManualSegmentItem) {
                val ok = sponsorBlockController?.manualSkipTo(contextHash, item.endMs) == true
                module.info("playerSheet: manual skip to=${item.endMs} ok=$ok")
            }

            override fun onRefreshSegments() {
                val ok = sponsorBlockController?.refreshSegments(contextHash) == true
                module.info("playerSheet: refresh segments ok=$ok")
            }

            override fun onToggleShowToast(enabled: Boolean) =
                updateSetting(module, context, SettingsKeys.SHOW_TOAST, enabled)

            override fun onToggleSeekbarMarker(enabled: Boolean) =
                updateSetting(module, context, SettingsKeys.SHOW_SEEKBAR_MARKER, enabled)

            override fun onToggleSkipStats(enabled: Boolean) =
                updateSetting(module, context, SettingsKeys.SHOW_SKIP_STATS, enabled)

            // 值由 ValueEditingCallbacks 带回，这里无需处理"点了编辑"
            override fun onEditMinSkipDuration() = Unit

            override fun onEditUserId() = Unit

            override fun onDismiss() {
                module.info("playerSheet: dismissed")
            }

            override fun onMinSkipDurationEdited(seconds: Float) =
                updateSetting(module, context, SettingsKeys.MIN_SKIP_DURATION, seconds.toString())

            override fun onUserIdEdited(userId: String) =
                updateSetting(module, context, SettingsKeys.USER_ID, userId)
        }

        return SponsorBlockPlayerSheet.show(activity, state, callbacks) { message ->
            module.info("playerSheet: $message")
        }
    }

    /**
     * 面板里改设置：写宿主 prefs（SettingsWriter 的监听会自动镜像 + 同步给 provider），
     * 然后**用本进程 prefs 立刻生成快照并应用**，让改动立即生效。
     */
    private fun updateSetting(
        module: XposedModule,
        context: android.content.Context,
        key: String,
        value: Any,
    ) {
        val writer = settingsWriter
            ?: synchronized(this) {
                // 双检锁:多线程同时走到这里时只建一个 SettingsWriter,避免重复注册监听
                settingsWriter ?: SettingsWriter(context.applicationContext).also { settingsWriter = it }
            }
        val editor = writer.sharedPreferences.edit()
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putString(key, value.toString())
            is Long -> editor.putString(key, value.toString())
            is String -> editor.putString(key, value)
        }
        editor.apply()
        val fresh = SettingsCodec.snapshotFromPreferences(writer.sharedPreferences)
        applySnapshot(module, fresh, source = "player sheet: $key=$value")
    }

    // ------------------------------------------------------------------ 进度回调

    private fun hookProgressText(module: XposedModule, cl: ClassLoader) {
        var callbackHooked = 0

        for (className in HostTargets.PROGRESS_TEXT_WIDGET_CLASSES) {
            val clazz = runCatching { Class.forName(className, false, cl) }.getOrNull()
            if (clazz == null) {
                HookProbe.miss(module, "progressWidget:$className", "class not found")
                continue
            }

            // 6.5.0: G(int,int)；旧目标: onPlayerProgressChange / updateTime / i0
            HookResolve.declaredMethod(
                clazz,
                HostTargets.PROGRESS_CALLBACK_INT_METHODS,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )?.let { method ->
                hookProgressCallback(module, method, "progressInt:$className#${method.name}")
                callbackHooked++
            }

            // 8.98/6.5.0 形态: j0(long,long) / k0(long,long)。
            // 探针实测前**不喂给 controller**：静态分析已确认真正的进度派发是
            // `service.r0#G(int position, int duration)`（调用点参数直接来自 core.getCurrentPosition()/getDuration()），
            // 这两个 long 方法很可能不是进度回调（j0 体内有 const/16 999 之类的定时/格式化逻辑），
            // 所以只挂上去打日志，避免用错语义的数值做跳过决策。
            HookResolve.declaredMethod(
                clazz,
                HostTargets.PROGRESS_CALLBACK_LONG_METHODS,
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
            )?.let { method ->
                hookProgressCallback(module, method, "progressLong:$className#${method.name}", feedController = false)
                callbackHooked++
            }

            // 时间扣减：拦截 setText(CharSequence, TextView$BufferType)
            HookResolve.declaredMethod(
                clazz,
                listOf("setText"),
                CharSequence::class.java,
                TextView.BufferType::class.java,
            )?.let { method ->
                hookProgressTextViewSetText(module, method, className)
            } ?: HookProbe.miss(module, "timeDeduction:$className", "setText(CharSequence,BufferType) not declared")
        }

        if (callbackHooked == 0) {
            HookProbe.miss(module, "progressCallback", HostTargets.PROGRESS_TEXT_WIDGET_CLASSES.joinToString())
        }
    }

    private fun hookProgressCallback(
        module: XposedModule,
        method: Method,
        key: String,
        feedController: Boolean = true,
    ) {
        hookAfter(module, method, key) { chain ->
            val target = chain.getThisObject() ?: return@hookAfter
            val args = chain.getArgs()
            val positionMs = (args.getOrNull(0) as? Number)?.toLong() ?: return@hookAfter
            val durationMs = (args.getOrNull(1) as? Number)?.toLong() ?: return@hookAfter

            // 探针：确认真正回调的是哪个方法、参数是秒还是毫秒
            HookProbe.first(module, "progressCallbackArgs", 10) {
                "${method.declaringClass.simpleName}#${method.name} arg0=$positionMs arg1=$durationMs" +
                    if (feedController) "" else " (probe-only)"
            }

            if (!feedController) {
                return@hookAfter
            }

            // 参数合理性校验：混淆名同签名的重载可能语义不同（例如把布局参数当进度传进来），
            // 只有 (0 <= position <= duration) 且 duration > 0 才喂给 controller 做跳过决策。
            if (durationMs <= 0 || positionMs < 0 || positionMs > durationMs + 1000) {
                HookProbe.first(module, "progressArgsRejected:$key", 3) {
                    "arg0=$positionMs arg1=$durationMs"
                }
                return@hookAfter
            }

            // 进度回调节点同时用于「补绑」：bindPlayerContainer 时 core 还没注入，
            // 到第一次进度回调时 widget 已经有 core 了。
            ensureDeferredBind(module, target)

            // onProgress 只负责触发跳过/静音；时长扣减显示交给 setText hook。
            val contextHash = contextHash(target)
            if (contextHash != 0) {
                sponsorBlockController?.onProgress(contextHash, positionMs, durationMs)
            } else {
                // 取不到 contextHash 时全链路会静默什么都不做，这里留一条探针便于定位
                // （写侧用容器的 Context、读侧用 widget 的 Context，两者必须同一个对象）
                HookProbe.first(module, "progressNoContext:$key", 3) { target.javaClass.name }
            }
        }
    }

    // 防止 setText 递归的标志
    private val isAdjusting = ThreadLocal.withInitial { false }

    private fun hookProgressTextViewSetText(module: XposedModule, method: Method, className: String) {
        module.hook(method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                // 先执行原方法(设置原始文本)
                val result = chain.proceed()

                // 防递归:如果是我们触发的 setText,跳过
                if (isAdjusting.get() == true) {
                    return@intercept result
                }

                val textView = chain.getThisObject() as? TextView ?: return@intercept result
                val originalText = textView.text ?: return@intercept result
                val contextHash = contextHash(textView)

                val newText = computeAdjustedText(contextHash, originalText)
                if (newText != null && newText.toString() != originalText.toString()) {
                    isAdjusting.set(true)
                    try {
                        textView.text = newText
                    } finally {
                        isAdjusting.set(false)
                    }
                }
                result
            }
        HookProbe.ok(module, "timeDeduction:$className", "setText(CharSequence, BufferType)")
    }

    private fun computeAdjustedText(contextHash: Int, originalText: CharSequence): CharSequence? {
        if (!settings.showTimeDeduction) {
            return null
        }

        // 已经带我们追加的 "(xx:xx)" 后缀时不再处理，避免重复。
        val textStr = originalText.toString()
        if (hasAdjustedDurationSuffix(textStr)) {
            return null
        }

        val controller = sponsorBlockController ?: return null
        val (_, segments) = controller.latestSegments(contextHash) ?: return null
        if (segments.isEmpty()) {
            return null
        }

        val durationMs = extractDurationFromText(textStr)
        if (durationMs <= 0) {
            return null
        }

        // 扣减口径必须与实际跳过策略一致：用同一个最小片段阈值过滤，
        // 且自动/手动跳过都关闭时不做扣减（否则会显示"剩余 xx"但实际没省那么多）。
        val skipEnabled = settings.autoSkip || settings.manualSkip
        val adjustedDurationMs = RemainingTimeFormatter.adjustedDuration(
            durationMs,
            segments,
            minSkipDurationMs = (settings.minSkipDurationSec * 1000).toLong(),
            skipEnabled = skipEnabled,
        )
        if (adjustedDurationMs >= durationMs) {
            return null  // 没有可扣减的片段
        }

        return RemainingTimeFormatter.appendAdjustedDuration(originalText, adjustedDurationMs)
    }

    // 进度文本每次 setText 都会走到这里(UI 线程高频):Regex 提为常量,Kotlin Regex 线程安全可复用
    private val adjustedSuffixRegex = Regex("""\s\(\d{1,3}:\d{2}(?::\d{2})?\)$""")
    private val durationWithHoursRegex = Regex("""(\d+):(\d+):(\d+)\s*/\s*(\d+):(\d+):(\d+)""")
    private val durationNoHoursRegex = Regex("""(\d+):(\d+)\s*/\s*(\d+):(\d+)""")

    private fun hasAdjustedDurationSuffix(text: String): Boolean {
        return adjustedSuffixRegex.containsMatchIn(text)
    }

    private fun extractDurationFromText(text: String): Long {
        // 尝试从 "00:16 / 30:01" 格式中提取总时长
        val match = durationWithHoursRegex.find(text)
        if (match != null) {
            val groups = match.groupValues
            val h = groups.getOrNull(4)?.toLongOrNull() ?: 0
            val m = groups.getOrNull(5)?.toLongOrNull() ?: 0
            val s = groups.getOrNull(6)?.toLongOrNull() ?: 0
            return (h * 3600 + m * 60 + s) * 1000
        }

        // 尝试 "00:16 / 30:01" 格式 (无小时)
        val match2 = durationNoHoursRegex.find(text)
        if (match2 != null) {
            val groups = match2.groupValues
            val m = groups.getOrNull(3)?.toLongOrNull() ?: 0
            val s = groups.getOrNull(4)?.toLongOrNull() ?: 0
            return (m * 60 + s) * 1000
        }

        return -1L
    }

    // ------------------------------------------------------------------ 进度条标记

    /**
     * 进度条片段标记。
     *
     * 6.5.0 里：
     *   - `seek.v3.g` = 实色矩形轨道层（Drawable，首选）
     *   - `seek.v3.f` = SeekBar 本体（View，备选，按整宽绘制）
     *   - `seek.v3.a` = 热度曲线（Path/Matrix），**不能挂**，会把标记画到高轨道上
     * 旧目标的 `seek.v3.q`（LayerDrawable）/`seek.v3.e`（lambda）在 6.5.0 都不覆写 draw。
     */
    private fun hookProgressDrawable(module: XposedModule, cl: ClassLoader) {
        var hooked = 0
        for (className in HostTargets.SEEK_TRACK_CLASSES) {
            val clazz = runCatching { Class.forName(className, false, cl) }.getOrNull() ?: continue
            val method = HookResolve.declaredMethod(clazz, listOf(HostTargets.DRAW_METHOD), Canvas::class.java)
                ?: continue

            hookAfter(module, method, "seekTrack:$className") { chain ->
                val target = chain.getThisObject() ?: return@hookAfter
                // 探针：按类名分开记录，才能看出「薄轨道 drawable(g)」和「SeekBar 本体(f/子类)」
                // 哪一个在带片段数据的情况下真正在画（原来共用 key，被 first(5/8) 上限吃掉了）
                val targetName = target.javaClass.name
                HookProbe.first(module, "seekTrackCalled:$className", 3) { targetName }

                if (!settings.showSeekbarMarker) {
                    return@hookAfter
                }
                val canvas = chain.getArgs().getOrNull(0) as? Canvas ?: return@hookAfter

                val contextHash = contextHash(target)
                val markers = sponsorBlockController?.progressMarkers(contextHash) ?: return@hookAfter
                val (durationMs, segments) = markers
                if (segments.isEmpty()) {
                    return@hookAfter
                }

                // 薄轨道优先：如果**同一个 SeekBar 实例**的轨道 drawable（seek.v3.g）刚画过标记，
                // 就不在 SeekBar 本体上重复画（否则会出现一条整高的色块盖住进度条）。
                //
                // 键必须是「实例」而不是 contextHash：竖屏与全屏是两个 SeekBar 实例、却共用同一个
                // Activity Context，用 contextHash 做键会互相抑制（某个方向没标记）。
                val isDrawableTarget = target is Drawable
                val ownerView = ownerViewOf(target)
                if (isDrawableTarget) {
                    if (ownerView != null) {
                        thinTrackPaintedAtByOwner[ownerView] = System.currentTimeMillis()
                    }
                } else if (ownerView != null) {
                    val lastThin = thinTrackPaintedAtByOwner[ownerView] ?: 0L
                    if (System.currentTimeMillis() - lastThin < THIN_TRACK_PREFERENCE_MS) {
                        return@hookAfter
                    }
                }

                val geometry = markerBoundsOf(target) ?: return@hookAfter

                // 按「实例」记录，才能在竖屏/全屏两个 SeekBar 实例之间区分开
                val instanceId = Integer.toHexString(System.identityHashCode(target))
                HookProbe.first(module, "seekDraw:$className:$instanceId", 3) {
                    val view = target as? View
                    val location = IntArray(2)
                    if (view != null) runCatching { view.getLocationOnScreen(location) }
                    buildString {
                        append("inst=").append(instanceId)
                        append(" source=").append(geometry.second)
                        append(" rect=").append(geometry.first)
                        if (view != null) {
                            append(" view=").append(view.width).append('x').append(view.height)
                            append(" pad=").append(view.paddingLeft).append(',').append(view.paddingTop)
                            append(',').append(view.paddingRight).append(',').append(view.paddingBottom)
                            append(" loc=").append(location[0]).append(',').append(location[1])
                        }
                        append(" durationMs=").append(durationMs)
                        append(" segments=").append(segments.size)
                    }
                }
                ProgressMarkerPainter.drawInBounds(
                    canvas,
                    geometry.first,
                    durationMs,
                    segments,
                    settings.categoryColors,
                )
            }
            hooked++
        }
        if (hooked == 0) {
            HookProbe.miss(module, "seekTrack", HostTargets.SEEK_TRACK_CLASSES.joinToString())
        }
    }

    /**
     * 计算标记应该画在哪个矩形区域。
     *
     * 优先级：
     *   1. `Drawable` 目标（`seek.v3.g`，实色矩形轨道层）→ 用它自己的 bounds。
     *      这条路径下 canvas 已经被 `ProgressBar.onDraw` 的 `canvas.translate(paddingLeft, paddingTop)`
     *      平移过，所以 bounds 直接可用，**不要再加 padding**；
     *   2. `ProgressBar`（`seek.v3.f` / `PlayerSeekWidget3`）→ 用它的 progressDrawable bounds，
     *      但 **必须补上 (paddingLeft, paddingTop)**：`progressDrawable.bounds` 是「内容盒」坐标
     *      （`onSizeChanged` 里设成 `(0, 0, w - padL - padR, h - padT - padB)`），
     *      而 Hook `View.draw(Canvas)` 拿到的是控件本地坐标。
     *      真机实测（全屏 2493x72、pad=27,9,27,9）：漏掉 padding 会让标记整体左移 27px、上移 9px，
     *      表现就是「彩色标记浮在轨道上方」；
     *   3. 其它 View → 居中的细带（绝不用整高，否则会盖住整个进度条）。
     *
     * @return (区域, 来源描述)，来源用于探针日志判断实际走的是哪条路。
     */
    private fun markerBoundsOf(target: Any): Pair<Rect, String>? = when (target) {
        is Drawable -> {
            val bounds = target.bounds
            if (bounds.isEmpty) null else Rect(bounds) to "drawable"
        }

        is android.widget.ProgressBar -> {
            val drawableBounds = runCatching { target.progressDrawable?.bounds }.getOrNull()
            if (drawableBounds != null && !drawableBounds.isEmpty) {
                Rect(drawableBounds)
                    .also { it.offset(target.paddingLeft, target.paddingTop) } to "progressDrawable+pad"
            } else {
                centeredBand(target)?.let { it to "view-band" }
            }
        }

        is View -> centeredBand(target)?.let { it to "view-band" }
        else -> null
    }

    /** 居中的细带：高度取 min(控件高, 6dp)，避免整高色块。 */
    private fun centeredBand(view: View): Rect? {
        if (view.width <= 0 || view.height <= 0) return null
        val density = view.resources.displayMetrics.density
        val bandHeight = minOf(view.height, (6 * density).toInt().coerceAtLeast(1))
        val top = (view.height - bandHeight) / 2
        return Rect(view.paddingLeft, top, view.width - view.paddingRight, top + bandHeight)
    }

    /**
     * 「某个 SeekBar 实例的轨道 drawable 最近画过标记」的时间表。
     *
     * 键是**承载 drawable 的 View**（`Drawable.callback` 就是宿主 SeekBar），而不是 contextHash：
     * 竖屏/全屏是两个 SeekBar 实例、共用同一个 Activity Context，用 contextHash 会互相抑制。
     * 用弱引用键避免长期持有宿主 View。
     */
    private val thinTrackPaintedAtByOwner: MutableMap<View, Long> =
        Collections.synchronizedMap(WeakHashMap<View, Long>())
    private const val THIN_TRACK_PREFERENCE_MS = 2000L

    /** 取绘制目标所属的 View：Drawable 用它的 callback（宿主 SeekBar），View 就是它自己。 */
    private fun ownerViewOf(target: Any): View? = when (target) {
        is View -> target
        is Drawable -> target.callback as? View
        else -> null
    }

    // ------------------------------------------------------------------ 工具

    private fun hookAfter(
        module: XposedModule,
        method: Method,
        key: String,
        onAfter: (io.github.libxposed.api.XposedInterface.Chain) -> Unit,
    ) {
        module.hook(method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                // 我们的回调异常不能影响宿主，但**必须留日志**：
                // 之前这里是裸 runCatching，异常被静默吞掉，现场完全查不出来（标记整帧消失就是这么来的）。
                runCatching { onAfter(chain) }.onFailure { throwable ->
                    module.warn("hook $key callback failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
                }
                result
            }
        HookProbe.ok(module, key, "${method.declaringClass.name}#${method.name}")
    }

    private fun contextHash(target: Any): Int {
        return runCatching {
            val context = when (target) {
                is View -> target.context
                is Drawable -> {
                    val callback = target.callback
                    when (callback) {
                        is android.view.View -> callback.context
                        else -> null
                    }
                }
                else -> null
            } ?: return 0
            PlayerBridge.contextHash(context)
        }.getOrDefault(0)
    }
}
