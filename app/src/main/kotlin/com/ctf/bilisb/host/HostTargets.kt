package com.ctf.bilisb.host

/**
 * 目标宿主（bilibili 6.5.0 / `com.bilibili.app.in`）的类名与方法名清单。
 *
 * 为什么集中成一张表：宿主每次发版 R8 都会重排混淆名，Hook 点散落在各文件里就会「静默失效」。
 * 集中后改版只需改这张表；解析按候选顺序取第一个存在的实现，命中/缺失由 [HookProbe] 记录。
 *
 * 候选里标注 `8.96` 的是旧目标（`tv.danmaku.bili` stock 8.96.0）的名字，作为兜底保留，
 * 便于旧包与新包同时可用。
 *
 * 依据：`docs/APK_6.5.0_ANALYSIS.md`（静态分析 + 字节码交叉引用）。
 */
object HostTargets {
    /** 目标宿主包名（bilibili 国际版）。 */
    const val HOST_PACKAGE = "com.bilibili.app.in"

    /** 旧目标包名，仅用于兼容旧镜像路径。 */
    const val LEGACY_HOST_PACKAGE = "tv.danmaku.bili"

    /** 宿主数据目录候选（写 JSON 镜像 / 统计文件）。 */
    val HOST_DATA_DIRS = listOf(
        "/data/data/$HOST_PACKAGE",
        "/data/user/0/$HOST_PACKAGE",
        "/data/data/$LEGACY_HOST_PACKAGE",
        "/data/user/0/$LEGACY_HOST_PACKAGE",
    )

    /** 宿主非主进程（命中即跳过 Hook）。 */
    val HOST_SUB_PROCESSES = listOf(
        ":web", ":download", ":pushservice", ":ijkservice", ":widgetProvider",
        ":dd_update", ":heap_analysis", ":safemode",
        ":sandboxed_process0", ":sandboxed_process1", ":sandboxed_process2",
        ":sandboxed_process3", ":sandboxed_process4",
    )

    // ---------------------------------------------------------------- 进度 / 时长

    /** 进度文本控件：进度回调、`setText` 时间扣减的落点。 */
    val PROGRESS_TEXT_WIDGET_CLASSES = listOf(
        "com.bilibili.playerbizcommonv2.widget.base.PlayerProgressTextWidget",
        "com.bilibili.app.gemini.player.widget.progress.GeminiProgressTextWidget",
        "com.bilibili.playerbizcommon.widget.control.PlayerProgressTextWidget",
    )

    /**
     * 进度回调候选（int,int）：6.5.0 是 `G`；`onPlayerProgressChange` 是旧目标；
     * `updateTime`/`i0` 是旧版控件形态。
     */
    val PROGRESS_CALLBACK_INT_METHODS = listOf("G", "onPlayerProgressChange", "updateTime", "i0")

    /**
     * 进度回调候选（long,long）：6.5.0 里 `j0`（v2 基类）/`k0`（Gemini）是长整型回调，
     * 与 8.98 patch 的形态一致。与 int 版同时挂，探针日志会告诉我们哪个真的会回调。
     */
    val PROGRESS_CALLBACK_LONG_METHODS = listOf("j0", "k0", "J")

    // ---------------------------------------------------------------- 播放器容器

    /**
     * 6.5.0 的容器注入入口：widget 的 `bindPlayerContainer(tv.danmaku.biliplayerv2.f)`。
     * 旧目标（8.96）是 Hook 容器类 `be1.j` 的生命周期方法。
     */
    val CONTAINER_BINDING_CLASSES = listOf(
        "com.bilibili.playerbizcommonv2.widget.seek.v3.PlayerSeekWidget3",
        "com.bilibili.playerbizcommon.widget.control.PlayerProgressTextWidget",
    )
    const val BIND_CONTAINER_METHOD = "bindPlayerContainer"

    /** 6.5.0 容器接口 `tv.danmaku.biliplayerv2.f`（实现类含 `t()/u()/v()/w()`）。 */
    const val CONTAINER_INTERFACE = "tv.danmaku.biliplayerv2.f"

    /** 从容器取 Android Context 的候选方法名：6.5.0 是 `t()`，旧目标是 `getContext()`。 */
    val CONTAINER_CONTEXT_METHODS = listOf("t", "getContext")

    /**
     * widget 的「从窗口分离」回调 —— 6.5.0 上作为**播放器离开**的信号。
     *
     * 6.5.0 没有旧目标 `be1.j#onDestroy` 那种容器生命周期方法，若没有这个信号，
     * 静音/倒计时/浮层/按钮以及 controller 里按 contextHash 的容器引用都不会清理。
     */
    const val WIDGET_DETACH_METHOD = "onDetachedFromWindow"

    /** 旧目标容器类（8.96），保留作为兜底。 */
    const val LEGACY_CONTAINER_CLASS = "be1.j"

    // ---------------------------------------------------------------- 播放器 core

    /** 从 widget/容器取 core 服务。6.5.0 与旧目标同名保留。 */
    const val GET_CORE_METHOD = "getPlayerCoreService"

    /** 平滑 seek：6.5.0 是 `o(int,boolean)`（`seekTo(int)` 等价于 `o(pos,false)`）。 */
    val SEEK_SMOOTH_METHODS = listOf("o", "seekTo")

    /** 只有位置参数的 seek（兜底）。 */
    val SEEK_PLAIN_METHODS = listOf("seekTo")

    val GET_DURATION_METHODS = listOf("getDuration", "getRealDuration")
    val GET_POSITION_METHODS = listOf("getCurrentPosition")

    // ---------------------------------------------------------------- 视频信息（aid/cid）

    /** 6.5.0 的 director 服务实现类（类名未被混淆）。 */
    val DIRECTOR_SERVICE_CLASSES = listOf(
        "tv.danmaku.biliplayerimpl.videodirector.PlayDirectorServiceV3",
        "tv.danmaku.biliplayerimpl.videodirector.VideosPlayDirectorService",
    )

    /** 注册/注销观察者：6.5.0 是 `j0(E0)` / `z0(E0)`；旧目标是 `add/removeVideoDirectorObserver`。 */
    val DIRECTOR_ADD_OBSERVER_METHODS = listOf("j0", "addVideoDirectorObserver")
    val DIRECTOR_REMOVE_OBSERVER_METHODS = listOf("z0", "removeVideoDirectorObserver")

    /** 从 widget 取 director 服务的方法候选。 */
    val DIRECTOR_GET_SERVICE_METHODS = listOf(
        "getPlayDirectorServiceV3",
        "getPlayDirectorServiceV2",
        "getPlayDirectorService",
    )

    /** 6.5.0 观察者接口。 */
    const val DIRECTOR_OBSERVER_INTERFACE = "tv.danmaku.biliplayerv2.service.E0"

    /** 旧目标观察者接口（8.96）。 */
    const val LEGACY_OBSERVER_INTERFACE = "tv.danmaku.biliplayerv2.service.VideoDirectorObserver"

    /** 6.5.0：`service.A#D()` == `PlayDirectorServiceV3#D()` 返回当前 `Video$e`。 */
    const val DIRECTOR_CURRENT_VIDEO_METHOD = "D"

    /** `Video$e`：`z()` 返回 `Video$a`（DanmakuResolveParams）。 */
    const val VIDEO_INTERFACE = "tv.danmaku.biliplayerv2.service.Video\$e"
    const val VIDEO_PARAMS_CLASS = "tv.danmaku.biliplayerv2.service.Video\$a"
    const val VIDEO_PARAMS_ACCESSOR = "z"

    /** `Video$a` = `DanmakuResolveParams`：`a`=avid、`b`=cid（字节码实测）。 */
    const val VIDEO_PARAMS_AID_FIELD = "a"
    const val VIDEO_PARAMS_CID_FIELD = "b"

    // ---------------------------------------------------------------- 进度条标记

    /**
     * 覆写 `draw(Canvas)` 的进度条绘制候选（按优先级）：
     *   g = 6.5.0 实色矩形轨道层（首选）；f = 6.5.0 SeekBar 本体（备选）；
     *   q/e = 8.96 旧目标（6.5.0 中 q 是 LayerDrawable、e 是 lambda，均不覆写 draw）。
     * 注意不要挂 `seek.v3.a`：那是热度曲线（Path/Matrix），标记会画在高轨道上。
     */
    val SEEK_TRACK_CLASSES = listOf(
        "com.bilibili.playerbizcommonv2.widget.seek.v3.g",
        "com.bilibili.playerbizcommonv2.widget.seek.v3.f",
        "com.bilibili.playerbizcommonv2.widget.seek.v3.q",
        "com.bilibili.playerbizcommonv2.widget.seek.v3.e",
    )
    const val DRAW_METHOD = "draw"

    // ---------------------------------------------------------------- 播放器「更多」面板（6.5.0）

    /**
     * 播放器右上角「⋯」弹出的「更多」半屏面板（分享行 + 快捷操作行 + 播放设置列表）。
     *
     * 6.5.0 实测定位（classes21.dex 反汇编）：
     *   - 面板宿主 `com.bilibili.ship.theseus.united.page.toolbar.MenuService`，
     *     由 `doMorePlayerSetting` 里 `DialogFragment.show(fm, "player_setting_dialog")` 弹出；
     *   - 内容是一个 RecyclerView，适配器 `com.bilibili.app.gemini.ui.f`，
     *     全量刷新入口 `f0(List)`；行由条目自己构建（`com.bilibili.app.gemini.ui.i`）。
     *
     * 这几个都是混淆短名，匹配时要同时校验类名 + 方法签名（必要时再加 versionCode）。
     */
    const val MORE_PANEL_ADAPTER_CLASS = "com.bilibili.app.gemini.ui.f"
    const val MORE_PANEL_REFRESH_METHOD = "f0"
    const val MORE_PANEL_MENU_SERVICE_CLASS = "com.bilibili.ship.theseus.united.page.toolbar.MenuService"

    /** 行条目接口（interface，可以动态代理实现）。 */
    const val MORE_PANEL_ITEM_INTERFACE = "com.bilibili.app.gemini.ui.i"

    /** 行 holder 接口：宿主只要求 `getRoot()` 返回行视图。 */
    const val MORE_PANEL_HOLDER_INTERFACE = "com.bilibili.app.gemini.ui.i\$b"

    /**
     * 判定「这是播放器更多面板」的内容特征：列表里出现该前缀包下的行（channel/x、channel/s 等）。
     *
     * 必须做这个判定：同一个 adapter `f` 也被详情页复用（实测详情页 47 项、播放器面板 18 项）。
     */
    const val MORE_PANEL_ROW_PACKAGE_PREFIX = "com.bilibili.playerbizcommonv2.widget.setting."
    val MORE_PANEL_OPEN_METHODS = listOf("doMorePlayerSetting", "showNewMenuInternal")

    // ---------------------------------------------------------------- 「我的」页入口

    /** 「我的」页 adapter：6.5.0 外层类被混淆成 `d`，Fragment 名保留。 */
    val MINE_ADAPTER_CLASSES = listOf(
        "tv.danmaku.bili.ui.main2.mine.d",
        "tv.danmaku.bili.ui.main2.mine.HomeUserCenterAdapter",
    )

    /** 菜单数据模型（6.5.0 完整保留）。 */
    const val MENU_GROUP_CLASS = "com.bilibili.lib.homepage.mine.MenuGroup"
    const val MENU_ITEM_CLASS = "com.bilibili.lib.homepage.mine.MenuGroup\$Item"

    /** 点击链路（`bilisb://settings`）拦截候选。 */
    val ROUTER_CLASSES = listOf(
        "com.bilibili.lib.blrouter.Router",
        "com.bilibili.lib.blrouter.BLRouter",
        "tv.danmaku.bili.ui.intent.IntentHandlerActivity",
    )
}
