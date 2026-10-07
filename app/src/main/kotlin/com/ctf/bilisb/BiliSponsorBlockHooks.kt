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
import com.ctf.bilisb.player.PlayerActions
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
import com.ctf.bilisb.ui.AndroidStrings
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
import java.lang.reflect.Proxy
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

    /** 安装时持有，供模块自持线程（进度轮询）打日志/读 core 用。 */
    @Volatile
    private var moduleRef: XposedModule? = null

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
        moduleRef = module
        // 登记模块实例：宿主进程里要靠它拿模块自己的资源表（见 ModuleStrings 的注释：
        // 用宿主的 Resources 解析模块的 R.string id 会查到宿主资源，真机上表现为标题变成 res/anim/...）。
        com.ctf.bilisb.ui.ModuleStrings.attach(module)
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
        // B 站增强(移植自 BiliTamer,MIT):IP 属地/隐藏互动提示/首页不自动刷新/分享到 QQ。
        // 开关在各 hook 回调内实时读 EnhanceFlags。顶栏/底栏 tab 已按需求移除,见 EnhanceHooks。
        installSafely(module, "enhanceHooks") { com.ctf.bilisb.hook.EnhanceHooks.install(module, cl) }
        installSafely(module, "cleartextPolicy") { com.ctf.bilisb.hook.CleartextPolicyHooks.install(module, cl) }

        // 解锁番剧 U1(只读观测):PlayerMoss.playViewUnite 的受限判定探针,不改任何行为。
        // 解锁钩子独立安装，行为由配置开关控制；验证范围见 docs/UNLOCK_PLAN.md。
        installSafely(module, "unlockPlayView") { com.ctf.bilisb.unlock.PlayViewHook.install(module, cl) }
        installSafely(module, "seasonMoss") { com.ctf.bilisb.unlock.SeasonMossHook.install(module, cl) }
        installSafely(module, "viewTab") { com.ctf.bilisb.unlock.ViewTabHook.install(module, cl) }
        // 搜索协议观测：6.6.0 实际命中非 K SearchMoss，K 通道保留诊断。
        installSafely(module, "searchObservation") { com.ctf.bilisb.unlock.SearchObservationHook.install(module, cl) }
        // S 线 S2+S3：搜索页签注入 + 标记页签搜索替换（unlock_search 开关，默认关）。
        installSafely(module, "searchUnlock") { com.ctf.bilisb.unlock.SearchUnlockHook.install(module, cl) }
        installSafely(module, "biliIntlDns") { com.ctf.bilisb.unlock.BiliIntlDnsHook.install(module, cl) }
        // K/gRPC 播放链路（离线下载引擎所在的那条；与上面的 MOSS PlayerMoss 是两套传输）。
        installSafely(module, "kPlayView") { com.ctf.bilisb.unlock.KPlayViewHook.install(module, cl) }
        // 首页顶栏「追番（大陆）」与「追番（港澳台）」页签注入（unlock_add_bangumi 开关，默认关）。
        installSafely(module, "homeTab") { com.ctf.bilisb.unlock.HomeTabHook.install(module, cl) }

        module.info(HookProbe.summary())
    }

    /**
     * 下载引擎（宿主 `:download` 进程）专用安装：只挂「取播放地址」所需的最小集合。
     *
     * 该进程没有播放器 UI，进度/搜索/页签/增强类 Hook 一律不装；但**必须**装播放链路，
     * 因为下载引擎是在这个进程里自己取一次播放地址（含下载能力位）。缺了这里的补丁，
     * 宿主拿到的仍是区域受限的响应，表现 = 缓存任务建好、entry.json 落盘后立刻
     * 「已暂停：缓存失败，请删除重试」（真机 2026-10-05 实证）。
     */
    fun installForDownloadProcess(module: XposedModule, param: PackageLoadedParam, processName: String) {
        val installKey = "${param.packageName}:$processName"
        if (!installed.add(installKey)) {
            return
        }
        val cl = param.defaultClassLoader
        moduleRef = module
        com.ctf.bilisb.ui.ModuleStrings.attach(module)
        module.info("Installing download-process hooks for ${param.packageName} process=$processName")
        // PlayViewHook 自带 access_key 捕获钩，下载进程取地址需要它来签名漫游请求。
        installSafely(module, "unlockPlayView") { com.ctf.bilisb.unlock.PlayViewHook.install(module, cl) }
        // 下载引擎在 :download 进程里走 K/gRPC 版 playViewUnite，必须一起挂。
        installSafely(module, "kPlayView") { com.ctf.bilisb.unlock.KPlayViewHook.install(module, cl) }
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
                paramTypeName = { it in HostTargets.DIRECTOR_OBSERVER_INTERFACES },
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
                paramTypeName = { it in HostTargets.CONTAINER_INTERFACES },
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
            HookResolve.methodIncludingInherited(clazz, listOf(HostTargets.WIDGET_DETACH_METHOD))?.let { detach ->
                hookAfter(module, detach, "playerTeardown:$className") { chain ->
                    val host = chain.getThisObject() ?: return@hookAfter
                    if (!clazz.isInstance(host)) return@hookAfter
                    onPlayerLeft(module, host, null)
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
    private fun onPlayerLeft(module: XposedModule, host: Any, container: Any?) {
        HookProbe.first(module, "playerTeardownCalled", 5) { host.javaClass.name }
        if (scheduleDeferredTeardown(module, host, container)) {
            module.info("player left: teardown deferred host=${host.javaClass.name}")
        }
    }

    /**
     * 待清理的 contextHash 与登记时刻。
     *
     * 键是 **contextHash** 而不是 host 对象：全屏切换会 detach 旧 widget 再 attach 一个
     * **新的** PlayerSeekWidget3 实例（真机日志证实 bindPlayerContainerCalled #2/#3 是新实例），
     * 按 host 身份匹配永远取消不掉，延迟清理照样执行、状态照样被删空。
     * contextHash（容器的 Context hash）在竖屏/全屏之间是同一个，才能正确撤销。
     */
    private data class PendingTeardown(val contextHash: Int, val registeredAtMs: Long)

    /** 已登记延迟清理的 contextHash 表。进度回调 / bind 到来时按 hash 移除。 */
    private val pendingTeardowns: MutableMap<Int, PendingTeardown> =
        java.util.concurrent.ConcurrentHashMap<Int, PendingTeardown>()

    /** 延迟清理窗口：全屏切换的 detach→attach 间隔远小于它；退出播放页则不会再有回调。 */
    private const val TEARDOWN_DELAY_MS = 3000L

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** 计算 host/container 的 contextHash，取不到返回 0（调用方按 0 跳过）。 */
    private fun teardownHashOf(host: Any, container: Any?): Int {
        val context = container?.let { PlayerBridge.context(it) } ?: PlayerBridge.context(host)
        return context?.let { PlayerBridge.contextHash(it) } ?: 0
    }

    /**
     * 登记 contextHash 并起延迟任务。返回 false 表示该 hash 已有待执行的清理（不重复登记）。
     *
     * @param host 触发 detach 的 widget（用于延迟任务里真正清理时解绑 director）
     * @param container 播放器容器（用于算 contextHash；detach 时机上可能拿不到，可传 null）
     */
    private fun scheduleDeferredTeardown(module: XposedModule, host: Any, container: Any?): Boolean {
        val hash = teardownHashOf(host, container)
        if (hash == 0) {
            // 拿不到 context:按 hash 的撤销/清理都不可靠,留探针便于定位状态泄漏。
            HookProbe.first(module, "teardownNoHash", 3) { "host=${host.javaClass.name}" }
            module.info("player left: teardown skipped, no context hash from host=${host.javaClass.name}")
            return false
        }
        val pending = PendingTeardown(hash, android.os.SystemClock.uptimeMillis())
        val first = pendingTeardowns.putIfAbsent(hash, pending) == null
        if (!first) return false
        mainHandler.postDelayed({
            // 必须按 (hash, 本次登记的那一条) 精确移除:detach→attach→detach 叠加时,
            // remove(hash) 会取走**后来者**的登记并用本次闭包里的旧 host 执行清理,
            // 把仍存活的 context 状态删空(症状:当前视频跳过/静音失效)。
            if (pendingTeardowns.remove(hash, pending)) {
                performTeardown(module, host, hash)
            }
        }, TEARDOWN_DELAY_MS)
        return true
    }

    /** 有任何「播放器仍然活着」的信号（bind / 进度回调）时调用：撤销该 context 的待执行清理。 */
    private fun cancelDeferredTeardown(module: XposedModule, hash: Int) {
        if (hash == 0) return
        val removed = pendingTeardowns.remove(hash) != null
        if (removed) {
            HookProbe.first(module, "teardownCancelled", 3) { "context=$hash" }
        }
    }

    /** 真正的清理：只应由 [scheduleDeferredTeardown] 的延迟任务调用。 */
    private fun performTeardown(module: XposedModule, host: Any, contextHash: Int) {
        // 只清「本 widget」的 pending（=== 身份）：延迟清理的 3s 窗口里新页可能已经挂起了
        // 自己的 pending（host 是新 widget 实例），无条件清空会把新页的补绑抹掉——
        // 此后连 seekDraw 都救不回来，只剩挂死（2026-09-30 真机那次是 teardown done
        // 恰好先于 deferral 才没触发这个形态）。
        pendingBindSlot.clearIfHost(host)
        VideoDirectorListener.unregister(module, host)
        // 必须走按 hash 的清理:此间宿主 widget 多半已 detach,反射取 Context 会失败,
        // onPlayerDestroyed(host) 会把 Int 当 host 用(hash=0 → 状态不清理/静音不解除)。
        sponsorBlockController?.onPlayerContextDestroyed(contextHash)
        // 播放器面板与播放页共存亡:播放页离开时若面板还开着(比如 Activity 直接被销毁),
        // 静态 current 会永久 isShowing=true,之后面板再也打不开 + 泄漏已死的 Activity。
        // dismiss 内部自己切主线程、幂等。
        com.ctf.bilisb.ui.SponsorBlockPlayerSheet.dismiss { message ->
            module.info(message)
        }
        module.info("player left: teardown done context=$contextHash host=${host.javaClass.name}")
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
                VideoDirectorListener.unregister(module, container)
                sponsorBlockController?.onPlayerDestroyed(container)
            }
        }
    }

    /**
     * 待补绑的播放器：`bindPlayerContainer` 触发时 core 往往还没注入，
     * 这时先记下来，等 core 就绪再补绑。
     *
     * 完成信号的历史是个教训：曾经只等「第一次进度回调」（6.5.0 每帧都来），
     * 6.6.0 把进度链路整体搬走后，两个宿主触发器（文本控件回调、seek draw）在正常播放
     * 期间都是死的，pending 挂死整个会话（2026-09-30 真机实测 4.7 分钟静默零跳过）。
     * 现在补绑由自持轮询驱动（挂起时同步 startProgressPoller，tick 先试补绑），
     * 这里只负责状态本身——CAS 语义收在 [com.ctf.bilisb.player.PendingBindSlot]，有单测。
     */
    private val pendingBindSlot =
        com.ctf.bilisb.player.PendingBindSlot { android.os.SystemClock.uptimeMillis() }

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

        // 全屏切换会先 detach 再 attach 并重新 bind：bind 到来说明播放器还活着，
        // 按 contextHash 撤销延迟清理（新 widget 实例与旧的不是一个对象，不能按身份匹配）。
        cancelDeferredTeardown(module, PlayerBridge.contextHash(context))

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
            pendingBindSlot.set(contextHash, container ?: host, host)
            module.info("core not ready at bind time, defer binding context=$contextHash host=${host.javaClass.name}")
            // 「有 pending ⇒ 有 poller」：补绑不能等宿主信号——6.6.0 上文本控件回调与 seek draw
            // 在正常播放期间都是死的，等下去就是挂死整个会话。轮询是自持定时器，挂起即拉起，
            // 由 progressPollerTick 先尝试补绑（completeBind 里的 startProgressPoller 对活任务幂等）。
            startProgressPoller(contextHash)
            return
        }

        completeBind(module, contextHash, container ?: host, host, core)
    }

    /**
     * 状态被误清后的补绑兜底：全屏切换 detach→attach 若在延迟窗口外触发了清理，
     * controller 里该 context 的 state/handle 会被删空且不会再有 bindPlayerContainer。
     * 进度回调仍每帧到来（widget 还在画），这里用 widget 的 core（或 director 服务的）
     * 重建一个 handle 重新登记，恢复跳过/标记链路。core 未就绪时静默放弃（等下一帧）。
     */
    private fun ensureRebindAfterTeardown(module: XposedModule, widget: Any, contextHash: Int) {
        val core = PlayerBridge.coreService(widget)
            ?: PlayerBridge.coreServiceFromDirector(VideoDirectorListener.lastDirectorService()) ?: return
        // container 必须是 widget 本身(是 View,必有 Context):director 服务不是容器,
        // 对它反射取 Context 会失败,补绑后的 Toast/静音/后续补绑会静默失效。
        val container = widget
        val controller = sponsorBlockController ?: return
        controller.bindPlayerHandle(PlayerHandle(contextHash, container, core))
        // 这条路径**没有** bindPlayerContainer 回调，所以轮询不会由 bind 顺手拉起：
        // 上一轮如果因为玩家离开把 poller 停了（handle 消失那条自停），这里不重启就永远没人喂进度
        // —— 真机表现是「补绑日志每秒一条 rebound，但再也不跳过」。有 handle 就必须有 poller。
        startProgressPoller(contextHash)
        probePollerMissingForHandle(module, contextHash)
        VideoDirectorListener.noteContextHash(contextHash)
        // 光有 handle 不够:state 只能由 onVideoIds 创建。玩家重建后 Context 实例换了
        // (contextHash 变了),director 回调却只会带着「当时」的旧 hash —— 新 context
        // 永远拿不到 ids,这里每个进度 tick 都会进来空转(真机日志每秒一条 rebound)。
        // 主动从 director 服务的当前条目提取 aid/cid 喂给 onVideoIds,补绑才算闭环。
        if (!controller.latestStateExists(contextHash)) {
            val ids = VideoDirectorListener.currentIdsFromService(module)
            if (ids != null && ids.first > 0 && ids.second > 0) {
                controller.onVideoIds(contextHash, ids.first, ids.second)
                HookProbe.first(module, "rebindFeedIds", 3) {
                    "context=$contextHash aid=${ids.first} cid=${ids.second}"
                }
            }
        }
        HookProbe.first(module, "rebindAfterTeardown", 3) {
            "context=$contextHash widget=${widget.javaClass.name} core=${core.javaClass.name}"
        }
        module.info("player handle rebound after teardown context=$contextHash")
    }

    /** 宿主信号路径的补绑（进度回调/seek draw 触发时 widget 已完成服务注入）。
     *  槽的抢占式消费保证多条回调并发时只有一个 completeBind；
     *  6.6.0 上这条路径在正常播放期间基本不触发，主补绑动力在 [progressPollerTick]。 */
    private fun ensureDeferredBind(module: XposedModule, progressWidget: Any) {
        val pending = pendingBindSlot.consumeAny() ?: return
        // 必须校验「补绑用的 widget」就是当初发起绑定的那个播放器：
        // 否则会用 A 的 contextHash/container 配 B 的 core（小窗/快速切集时串台）。
        if (pending.host !== progressWidget &&
            PlayerBridge.contextHash(progressWidget) != pending.contextHash
        ) {
            // 校验失败:把 pending 放回去,等待真正匹配的 widget
            pendingBindSlot.restore(pending)
            return
        }
        val core = PlayerBridge.coreService(progressWidget)
            ?: PlayerBridge.coreServiceFromDirector(VideoDirectorListener.lastDirectorService())
            ?: run {
                // core 还没就绪:放回 pending,等下一次（回调或轮询 tick）
                pendingBindSlot.restore(pending)
                return
            }
        completeBind(module, pending.contextHash, pending.container, pending.host, core)
    }

    /** 轮询 tick 驱动的补绑：不依赖任何宿主信号，core 一就绪、最迟下个 tick 就完成绑定。 */
    private fun tryCompletePendingBindFromTick(
        controller: com.ctf.bilisb.sponsor.SponsorBlockController,
        contextHash: Int,
    ) {
        if (!pendingBindSlot.peekFor(contextHash).let { it != null }) return
        val module = moduleRef ?: return
        val pending = pendingBindSlot.consumeMatching(contextHash) ?: return
        // handle 已在别处完成（例如随后的完整 bind）：pending 已过期，丢弃即可
        if (controller.hasHandle(contextHash)) return
        // core 三个来源与 bind 路径同口径：widget -> 容器 -> director 服务
        val core = PlayerBridge.coreService(pending.host)
            ?: PlayerBridge.coreService(pending.container)
            ?: PlayerBridge.coreServiceFromDirector(VideoDirectorListener.lastDirectorService())
        if (core == null) {
            // 还没就绪:放回,下个 tick 再试（sinceMs 不重置,挂了多久要累计）
            pendingBindSlot.restore(pending)
            return
        }
        completeBind(module, contextHash, pending.container, pending.host, core)
    }

    /**
     * 进度轮询表（6.6.0 主喂入源）：真机实测该版本的播放进度既不走文本控件回调
     * （控件不实例化）、不逐帧走 `g#draw`（仅布局/seek 爆发）、`D0$c.run` 也只在 seek 后
     * 打一炮——没有可依赖的宿主 tick。改为自持 500ms 轮询已绑定 handle 的 core
     * （getCurrentPosition/getDuration 是 6.5.0/6.6.0 稳定真名），等价 6.5.0 的 tick 语义。
     *
     * 生命周期不变式：**有 handle ⇒ 有 poller**。三条起表路径（bind、补绑、controller 重建）
     * 与两条停表路径（handle 消失、controller 关闭）都要维持它 —— 违反它的真机表现是
     * 「日志安静地不再跳过」，与「服务端拉不到片段」完全无法区分（2026-09-29 排查就吃过这个亏）。
     * 表本身的幂等/自停/死表项语义封在 [ProgressPollerRegistry]，有单测守着。
     */
    private val progressPollers = com.ctf.bilisb.player.ProgressPollerRegistry { t ->
        // 轮询线程绝不外抛（外抛会静默杀死周期任务），但异常必须留痕，否则又是「日志安静地不跳过」。
        val m = moduleRef
        if (m != null) {
            HookProbe.first(m, "progressPollerError", 5) {
                "poller 异常: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
    }

    /**
     * deferred bind 的诊断阈值。修复后补绑由自持轮询驱动，core 一就绪最迟下个 tick（500ms）完成；
     * 超过 [PENDING_BIND_STUCK_MS] 还挂着说明 core 迟迟不出（页面假活？），必须留名；
     * 超过 [PENDING_BIND_EXPIRY_MS] 直接放弃（host 八成已死），轮询自停——不给死 pending 开永久空转的口子。
     */
    private const val PENDING_BIND_STUCK_MS = 10_000L
    private const val PENDING_BIND_EXPIRY_MS = 60_000L

    private fun startProgressPoller(contextHash: Int) {
        progressPollers.start(contextHash) { progressPollerTick(contextHash) }
    }

    /** 显式停表（幂等）：handle 消失、controller 关闭/重建都走这里。 */
    private fun stopProgressPoller(contextHash: Int) = progressPollers.stop(contextHash)

    /**
     * 启动后自检「handle 在、poller 不在」这个组合 —— 进度喂入是 6.6.0 的**唯一**主喂入源，
     * 它一旦静默缺失，现象和「服务端拉不到片段」完全一样（零跳过、零标记），
     * 所以这个组合必须在日志里有名字，而不是靠 `seekTick feed` 心跳的沉默去反推。
     */
    private fun probePollerMissingForHandle(module: XposedModule, contextHash: Int) {
        val controller = sponsorBlockController ?: return
        if (controller.hasHandle(contextHash) && !progressPollers.isRunning(contextHash)) {
            HookProbe.first(module, "pollerMissingForHandle", 3) {
                "context=$contextHash 已绑定 handle 但没有进度 poller（本会话不会自动跳过）"
            }
        }
    }

    /**
     * 一次轮询 tick。返回 true 表示「该 handle 已不存在，请调用方停掉本 context 的 poller」。
     *
     * **整个 tick 只读一次 [sponsorBlockController]**：此前「守卫读一次、喂入再读一次」，
     * 两次读之间的 controller 重建（[applySnapshot] 先换引用再 close 旧的）会让第二个读拿到
     * null → 当时那条自停路径把轮询摘表，而该 context 之后不一定还有 bind 来重启它 →
     * 本会话剩余时间静默零跳过（现象与「片段拉不到」无法区分）。
     *
     * **pending 补绑先行**：本 context 挂着 deferred bind 时，每个 tick 先尝试补绑——
     * 6.6.0 上宿主侧的两个补绑触发器在正常播放期间都是死的，这条自持路径是补绑的唯一动力。
     * 补绑成功则本 tick 继续走正常喂入（completeBind 已把 handle 登记进 controller）。
     *
     * 抽成独立函数也是为了能在单测里直接跑（不依赖真实宿主与定时器）。
     */
    private fun progressPollerTick(contextHash: Int): Boolean {
        val controller = sponsorBlockController ?: return false
        tryCompletePendingBindFromTick(controller, contextHash)
        // 自停**只看 handle 还在不在**，不看 core 读不读得到：controller 重建的瞬间
        // （applySnapshot 先换引用再 close 旧的）coreForContext 可以是 null，而 handle 正随
        // adoptStateFrom 迁到新 controller —— 那种「瞬时 null」曾经直接把轮询摘表，
        // 之后该 context 没有 bind 来重启（ensureRebindAfterTeardown 就走没有 bind 的路径），
        // 本会话剩余时间静默零跳过。
        if (!controller.hasHandle(contextHash)) {
            // 但本 context 还挂着 deferred bind 时不能自停：补绑还欠着（core 未就绪），
            // poller 一停就没有人完成它——挂起路径拉起的轮询会被第一条 tick 误杀。
            val pending = pendingBindSlot.peekFor(contextHash) ?: return true
            val module = moduleRef ?: return false
            val ageMs = android.os.SystemClock.uptimeMillis() - pending.sinceMs
            when {
                ageMs >= PENDING_BIND_EXPIRY_MS -> {
                    // 放弃：host 八成已死（页面销毁没走 teardown）。下个 bind 会重新走完整链路。
                    pendingBindSlot.consumeMatching(contextHash)
                    HookProbe.first(module, "pendingBindExpired", 3) {
                        "context=$contextHash pending 挂 ${ageMs}ms 未完成，放弃等待"
                    }
                    return true
                }
                ageMs >= PENDING_BIND_STUCK_MS -> HookProbe.first(module, "pendingBindStuck", 3) {
                    "context=$contextHash pending 已挂 ${ageMs}ms 未完成（core 始终未就绪？）"
                }
            }
            return false
        }
        val core = controller.coreForContext(contextHash) ?: return false
        val m = moduleRef ?: return false
        val pos = PlayerActions.currentPositionMs(m, core) ?: return false
        val dur = PlayerActions.durationMs(m, core) ?: return false
        feedTickProgress(m, controller, contextHash, pos, dur)
        return false
    }

    /** tick 喂入：bind 已完成，直接走进度决策链（与 hookProgressCallback 尾部同口径）。 */
    private fun feedTickProgress(
        module: XposedModule,
        controller: SponsorBlockController,
        contextHash: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        // 心跳：每 50 次 tick 打一条，证明派发器钩子真的在被调用（跳过不触发时先看这里）
        tickCounter.incrementAndGet().let { n ->
            if (n % 50L == 1L) {
                module.info("seekTick feed #$n pos=$positionMs dur=$durationMs hash=$contextHash")
            }
        }
        if (durationMs <= 0 || positionMs < 0 || positionMs > durationMs + 1000) {
            HookProbe.first(module, "progressArgsRejected:seekTick", 3) {
                "pos=$positionMs dur=$durationMs"
            }
            return
        }
        cancelDeferredTeardown(module, contextHash)
        if (!controller.latestStateExists(contextHash)) return
        controller.onProgress(contextHash, positionMs, durationMs)
    }

    private val tickCounter = java.util.concurrent.atomic.AtomicLong(0)

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

        // 6.6.0 主喂入源：自持 500ms 轮询（见 startProgressPoller 注释）
        startProgressPoller(contextHash)
        probePollerMissingForHandle(module, contextHash)
    }

    /**
     * reload 的最小间隔:bindPlayerContainer 在每次全屏切换/竖屏旋转都会触发,
     * 每次都无条件同步走跨进程 IPC(失败再同步读盘)会把主线程卡在 Binder 上。
     * 间隔内改走 [ModuleSettings.load](命中进程内缓存,零 IPC);设置变更的即时生效
     * 不受影响 —— 面板路径直接 applySnapshot,不走这里。
     */
    private const val SETTINGS_RELOAD_MIN_INTERVAL_MS = 3_000L

    @Volatile
    private var lastSettingsReloadAtMs: Long = 0L

    private fun ensureSettingsLoaded(module: XposedModule, containerContext: android.content.Context) {
        val reloadDue = android.os.SystemClock.uptimeMillis() - lastSettingsReloadAtMs >=
            SETTINGS_RELOAD_MIN_INTERVAL_MS
        val freshSettings = if (reloadDue) {
            lastSettingsReloadAtMs = android.os.SystemClock.uptimeMillis()
            runCatching {
                com.ctf.bilisb.settings.ModuleSettings.reload(module, containerContext)
            }.getOrElse {
                module.info("ModuleSettings reload failed, using defaults: ${it.message}")
                com.ctf.bilisb.settings.SettingsSnapshot.DEFAULT
            }
        } else {
            com.ctf.bilisb.settings.ModuleSettings.load(module, containerContext)
        }

        // 注意：必须先拿旧快照再赋值。`settings` 是同一个字段，若先赋值再比较，
        // `settings != freshSettings` 恒为 false（data class equals 自反），
        // 会导致除总开关外的所有设置改动都不生效（只有重建 controller 才会带上新配置）。
        module.info(
            "Settings snapshot on player enter: enabled=${freshSettings.enabled} " +
                "autoSkip=${freshSettings.autoSkip} server=${freshSettings.serverAddress} " +
                "userId=${redactUserId(freshSettings.userId)}",
        )
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
            // controller 关掉后进度喂入没有任何意义：显式停掉全部 poller（而不是让它们每 500ms
            // 空转等 coreForContext 返回 null 去自停 —— 那条自停路径有竞态，见 progressPollerTick）。
            progressPollers.stopAll()
            module.info("SponsorBlock disabled in settings ($source)")
            return
        }

        val currentController = sponsorBlockController
        if (currentController == null || previousSettings != freshSettings) {
            val replacement = SponsorBlockController(module, freshSettings)
            // 状态迁移必须在 close 之前:onVideoIds 只在播放条目变化时触发,同一视频内不会再来,
            // 不迁移的话改任意开关后,当前视频的跳过/静音/手动按钮会整体失效到下一集。
            currentController?.let { replacement.adoptStateFrom(it) }
            // 先赋值再关旧:close→assign 间隙里到来的进度/director 回调会落在已 close 的
            // 旧 controller 上被丢弃;先切换引用则窗口内事件直接进新 controller。
            sponsorBlockController = replacement
            currentController?.close()
            // 旧 controller 的 poller 一并停掉：任务体每 tick 只读一次 sponsorBlockController，
            // 但重建窗口里它可能正好拿到那个已被 close 的旧实例（喂进去的进度没有 handle 可落）。
            // 「停表 + 按新 controller 重启」才与「换了个 controller」语义一致；
            // 注意：只有这个显式入口和 handle 消失能让 poller 停，任务体不会自己摘表。
            progressPollers.stopAll()
            // 重启按新 controller 的 handle 表来（handle 已随 adoptStateFrom 迁移过来），
            // 而不是凭旧表项猜 —— 保证「有 handle ⇒ 有 poller」这个不变式在设置变更后仍成立。
            replacement.activeContextHashes().forEach { hash ->
                startProgressPoller(hash)
                probePollerMissingForHandle(module, hash)
            }
            // 「有 pending ⇒ 有 poller」在 controller 重建后同样要维持：pending 没有 handle，
            // 不会出现在 activeContextHashes 里，上面那张表不认识它——这里不补起，
            // 它的看门狗就断在设置变更那一刻，退回挂死形态。
            pendingBindSlot.peek()?.let { startProgressPoller(it.contextHash) }
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

        val formatterStrings = AndroidStrings(activity)
        val manualItems = snapshot?.segments.orEmpty().map { segment ->
            ManualSegmentItem(
                label = SheetStateFormatter.formatManualSegmentItem(
                    SponsorCategories.displayName(activity, segment.category),
                    segment.startMs,
                    segment.endMs,
                    formatterStrings,
                ),
                startMs = segment.startMs,
                endMs = segment.endMs,
            )
        }

        val state = PlayerSheetState(
            segmentCount = snapshot?.segmentCount ?: 0,
            playheadInsideSegment = inside != null,
            insideSegmentLabel = inside?.let {
                "${SponsorCategories.displayName(activity, it.category)} " +
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
            ) + " · " + com.ctf.bilisb.settings.ModuleSettings.lastReadSource,
            showToast = settings.showToast,
            showSeekbarMarker = settings.showSeekbarMarker,
            showSkipStats = settings.showSkipStats,
            minSkipDurationLabel = SheetStateFormatter.formatSeconds((settings.minSkipDurationSec * 1000).toLong()),
            minSkipDurationMaxSec = SettingsKeys.MAX_MIN_SKIP_DURATION_SECONDS,
            userIdLabel = settings.userId,
        )

        val callbacks = object : Callbacks, ValueEditingCallbacks {
            override fun onToggleAutoSkip(enabled: Boolean) =
                updateSetting(module, context, SettingsKeys.AUTO_SKIP, enabled)

            override fun onSubmitSegment() {
                // T5：提交结果 Toast（成功/失败/标记起点）。回调在提交线程到达，
                // PlayerToastBridge 内部自行切主线程。
                sponsorBlockController?.markOrSubmitCurrentPosition(
                    contextHash,
                    settings.defaultSubmitCategory,
                ) { ok, status ->
                    // host = 面板打开时的播放器容器（外层参数），Toast 落点
                    val toastHost = com.ctf.bilisb.player.PlayerBridge.context(host) ?: return@markOrSubmitCurrentPosition
                    when {
                        ok -> com.ctf.bilisb.ui.PlayerToastBridge.showErrorToast(
                            module, toastHost, com.ctf.bilisb.R.string.toast_submitted, "200",
                        )
                        status > 0 -> com.ctf.bilisb.ui.PlayerToastBridge.showErrorToast(
                            module, toastHost, com.ctf.bilisb.R.string.toast_submit_failed, "status=$status",
                        )
                        else -> com.ctf.bilisb.ui.PlayerToastBridge.showErrorToast(
                            module, toastHost, com.ctf.bilisb.R.string.toast_marked_start, "",
                        )
                    }
                }
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
        if (key == SettingsKeys.USER_ID && value is String &&
            !com.ctf.bilisb.sponsor.UserIdentityStore.isValidUserId(value)
        ) {
            module.info("player sheet: reject invalid userId")
            return
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
        applySnapshot(module, fresh, source = "player sheet: $key=${redactSettingValue(key, value)}")
    }

    private fun redactUserId(userId: String): String =
        if (userId.isEmpty()) "-" else userId.take(4) + "…"

    private fun redactSettingValue(key: String, value: Any): String =
        if (key == SettingsKeys.USER_ID) redactUserId(value.toString()) else value.toString()

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
                // 进度回调本身就是「播放器还活着」的信号：按 contextHash 撤销延迟清理
                // （全屏切换 detach→attach 后是新的 widget 实例，不能按对象身份匹配）。
                cancelDeferredTeardown(module, contextHash)
                // 状态缺失兜底：全屏切换若触发了清理（延迟窗口之外的边缘时序），
                // state 会被删空且不会再有 bindPlayerContainer。这里从 widget 的 core
                // 与 director 服务重建一个 handle 并重新登记，恢复跳过/标记链路。
                if (sponsorBlockController?.latestStateExists(contextHash) != true) {
                    ensureRebindAfterTeardown(module, target, contextHash)
                }
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

    /** seek draw 喂入次数（心跳探针用）。 */
    private val feedCounter = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 6.6.0 进度喂入（见 hookProgressDrawable 内注释）：从活着的 seek bar draw 回调读 core
     * 的 position/duration 喂控制器，复用 6.5.0 进度回调的完整路径（补绑/撤销清理/重建状态）。
     * 顺带做一次时间文本控件探针：6.6.0 的时间显示不在三个 PlayerProgressTextWidget 上，
     * 在 seek widget 的视图层级里找出真正的 TextView（类名进探针，供后续扣减显示 hook 用）。
     */
    private fun feedProgressFromSeekDraw(module: XposedModule, target: Any, className: String) {
        val core = PlayerBridge.coreService(target) ?: return
        val positionMs = PlayerActions.currentPositionMs(module, core) ?: return
        val durationMs = PlayerActions.durationMs(module, core) ?: return

        // 心跳：每 200 次喂入打一条（播放中约每数秒一条），证明喂入链活着并给出
        // pos/dur 与 controller 侧 state/handle 的存在性——跳过不触发时先看这里。
        feedCounter.incrementAndGet().let { n ->
            if (n % 200 == 1L) {
                val hash = contextHash(target)
                module.info(
                    "seekDraw feed alive #$n cls=${target.javaClass.simpleName} " +
                        "pos=$positionMs dur=$durationMs hash=$hash " +
                        "state=${sponsorBlockController?.latestStateExists(hash)} " +
                        "handle=${sponsorBlockController?.hasHandle(hash)}",
                )
            }
        }

        HookProbe.first(module, "progressCallbackArgs", 10) {
            "seekDraw($className) pos=$positionMs dur=$durationMs"
        }

        // 参数合理性校验口径与 hookProgressCallback 一致
        if (durationMs <= 0 || positionMs < 0 || positionMs > durationMs + 1000) {
            HookProbe.first(module, "progressArgsRejected:seekDraw:$className", 3) {
                "pos=$positionMs dur=$durationMs"
            }
            return
        }

        ensureDeferredBind(module, target)
        val contextHash = contextHash(target)
        if (contextHash == 0) {
            HookProbe.first(module, "progressNoContext:seekDraw:$className", 3) { target.javaClass.name }
            return
        }
        cancelDeferredTeardown(module, contextHash)
        if (sponsorBlockController?.latestStateExists(contextHash) != true) {
            ensureRebindAfterTeardown(module, target, contextHash)
        }
        sponsorBlockController?.onProgress(contextHash, positionMs, durationMs)

        probeTimeTextInside(module, target)
    }

    /** 限时探针：在 seek widget 层级里找显示「mm:ss / mm:ss」的 TextView，记下类名。 */
    private fun probeTimeTextInside(module: XposedModule, target: Any) {
        HookProbe.first(module, "seekTimeTextProbe", 3) {
            val root = target as? android.view.View ?: return@first "target not a View"
            val found = StringBuilder()
            fun walk(view: android.view.View) {
                if (found.length > 220) return
                if (view is android.widget.TextView) {
                    val t = view.text?.toString()?.trim().orEmpty()
                    if (Regex("^\\d{1,2}:\\d{2}\\s*/\\s*\\d{1,2}:\\d{2}$").matches(t) ||
                        Regex("^\\d{1,2}:\\d{2}$").matches(t)
                    ) {
                        found.append(view.javaClass.name).append("=\"").append(t).append("\"; ")
                    }
                }
                if (view is android.view.ViewGroup) {
                    for (i in 0 until view.childCount) walk(view.getChildAt(i))
                }
            }
            walk(root)
            if (found.isEmpty()) "no time text in ${root.javaClass.simpleName} subtree" else found.toString()
        }
    }

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

                // 6.6.0 进度喂入：文本进度控件不再参与播放（真机实测只有 PlayerSeekWidget3
                // 绑定容器，三个 PlayerProgressTextWidget 的 J/g0 均不回调——派发改走
                // service.s0#J，由 SeekService 的 ticker 发给注册过的监听器，我们的控件不在册）。
                // draw 只在进度变化触发重绘时执行（频率≈宿主 tick），在这里读 core 喂控制器；
                // 控件不可见不画 → 不喂，此时也无跳过需求。必须放在标记开关判定之前：
                // 关掉标记只是不画，跳过仍要吃进度。
                feedProgressFromSeekDraw(module, target, className)

                // 探针开关判定必须放在探针**之前**:draw 回调每帧都来,用户关掉标记后
                // 探针日志照样每帧打(即使有限频)纯属浪费。
                if (!settings.showSeekbarMarker) {
                    return@hookAfter
                }
                // 探针：按类名分开记录，才能看出「薄轨道 drawable(g)」和「SeekBar 本体(f/子类)」
                // 哪一个在带片段数据的情况下真正在画（原来共用 key，被 first(5/8) 上限吃掉了）
                val targetName = target.javaClass.name
                HookProbe.first(module, "seekTrackCalled:$className", 3) { targetName }

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
                        // 单调时钟:墙钟会被 NTP/改时间回拨,窗口计算失真(与其余抑制窗口口径一致)
                        thinTrackPaintedAtByOwner[ownerView] = android.os.SystemClock.uptimeMillis()
                    }
                } else if (ownerView != null) {
                    val lastThin = thinTrackPaintedAtByOwner[ownerView] ?: 0L
                    if (android.os.SystemClock.uptimeMillis() - lastThin < THIN_TRACK_PREFERENCE_MS) {
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
