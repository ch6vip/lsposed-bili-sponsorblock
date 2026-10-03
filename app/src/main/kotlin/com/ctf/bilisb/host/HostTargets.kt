package com.ctf.bilisb.host

/**
 * 目标宿主（bilibili 6.5.0 / 6.6.0，`com.bilibili.app.in`）的类名与方法名清单。
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
     * 进度回调候选（int,int）：6.5.0 是 `G`，6.6.0 改名 `J`；`onPlayerProgressChange`
     * 是旧目标；`updateTime`/`i0` 是旧版控件形态。
     */
    val PROGRESS_CALLBACK_INT_METHODS = listOf("G", "J", "onPlayerProgressChange", "updateTime", "i0")

    /**
     * 进度回调候选（long,long）：6.5.0 里 `j0`（v2 基类）/`k0`（Gemini）是长整型回调，
     * 6.6.0 的 v2 基类改名 `g0`。与 int 版同时挂，探针日志会告诉我们哪个真的会回调。
     */
    val PROGRESS_CALLBACK_LONG_METHODS = listOf("j0", "k0", "g0", "J")

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

    /** 容器接口候选：6.5.0 是 `f`，6.6.0 的 `bindPlayerContainer` 参数改传子接口 `h`。 */
    val CONTAINER_INTERFACES = listOf(
        "tv.danmaku.biliplayerv2.f",
        "tv.danmaku.biliplayerv2.h",
    )

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

    // ---------------------------------------------------------------- 进度 tick（6.6.0）

    /**
     * 6.6.0 的进度 tick 源：文本进度控件不再参与播放（真机实测三个
     * PlayerProgressTextWidget 均不实例化，J/g0 不回调；seek bar 播放期间也不逐帧走
     * `g#draw`）。tick 由 SeekService(D0) 的派发器执行——`D0$c.run`（字节码实证：
     * `D0.i(core).getDuration()/getCurrentPosition() → 遍历监听器 → s0#J(pos,dur)`）。
     *
     * 实现：hook 派发器的 `run()`，after 里用已绑定 handle 的 core 读 pos/dur 喂控制器
     * （不往宿主监听器列表里塞代理——D0 上 d/e 两个 WI1.h 持有者同名同形无法区分，
     * 注册错列表会静默无效）。6.5.0 无此类（SeekService 是 C0，tick 类名不同），
     * 走文本控件回调旧路径，miss 探针仅记录。
     */
    val SEEK_TICK_DISPATCHER_CLASSES = listOf("tv.danmaku.biliplayerv2.service.D0\$c")
    val SEEK_TICK_RUN_METHOD = "run"

    // ---------------------------------------------------------------- 视频信息（aid/cid）

    /** 6.5.0 的 director 服务实现类（类名未被混淆）。 */
    val DIRECTOR_SERVICE_CLASSES = listOf(
        "tv.danmaku.biliplayerimpl.videodirector.PlayDirectorServiceV3",
        "tv.danmaku.biliplayerimpl.videodirector.VideosPlayDirectorService",
    )

    /** 注册/注销观察者：6.5.0 是 `j0(E0)` / `z0(E0)`，6.6.0 注册改名 `l0`（注销仍叫 `z0`）；
     *  旧目标是 `add/removeVideoDirectorObserver`。 */
    val DIRECTOR_ADD_OBSERVER_METHODS = listOf("j0", "l0", "addVideoDirectorObserver")
    val DIRECTOR_REMOVE_OBSERVER_METHODS = listOf("z0", "removeVideoDirectorObserver")

    /** 从 widget 取 director 服务的方法候选。 */
    val DIRECTOR_GET_SERVICE_METHODS = listOf(
        "getPlayDirectorServiceV3",
        "getPlayDirectorServiceV2",
        "getPlayDirectorService",
    )

    /**
     * 观察者接口候选：6.5.0 是 `E0`（回调 a/b/c/e），6.6.0 改名 `F0`（回调形状不变）。
     *
     * 注意：这两个名字**跨版本互相撞名**（6.5.0 的 F0 是无关的媒体资源接口，6.6.0 的 E0
     * 是空标记接口），不能按固定顺序挑类——消费方必须用「能解析出注册方法的那个接口」
     * 反推（见 VideoDirectorListener.registerDirectorService）。
     */
    val DIRECTOR_OBSERVER_INTERFACES = listOf(
        "tv.danmaku.biliplayerv2.service.E0",
        "tv.danmaku.biliplayerv2.service.F0",
        LEGACY_OBSERVER_INTERFACE,
    )

    /** 旧目标观察者接口（8.96）。 */
    const val LEGACY_OBSERVER_INTERFACE = "tv.danmaku.biliplayerv2.service.VideoDirectorObserver"

    /** 当前 `Video$e` 访问器：6.5.0 是 `D()`，6.6.0 改名 `F()`（返回类型不变）。 */
    val DIRECTOR_CURRENT_VIDEO_METHODS = listOf("D", "F")

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
     * 全量刷新入口跨版本漂移：6.5.0 是 `f0(List)`，6.6.0 是 `e0(List)`。
     */
    const val MORE_PANEL_ADAPTER_CLASS = "com.bilibili.app.gemini.ui.f"
    val MORE_PANEL_REFRESH_METHODS = listOf("f0", "e0")
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

    // ---------------------------------------------------------------- B 站增强（候选表，2026-09-30 收编）

    // 「B 站增强」四件套的 hook 点原本散落在各自 hook 文件里（宿主改版要逐文件找），
    // 现在与主链路同口径集中在这里。标注「真名」的是未混淆类（跨版本稳定）；
    // 混淆短名（ip1.h / kr1.a / Aq0.a 这类）跨版本必漂移，一律给候选列表并保持既有尝试顺序。
    // SDK/平台类（okhttp3.*、android.app.Activity、com.tencent.tauth.*）不混淆、不漂移，
    // 留在各自 hook 文件里，不进这张表。

    // ------------------------------------------------------------ 隐藏互动提示

    /** 一键三连提示控件（真名，6.3.0-6.6.0 未漂移）。 */
    const val TRIPLE_LIKE_CLASS = "com.bilibili.app.gemini.player.widget.like.VideoTripleLike"
    const val TRIPLE_PROMPT_METHOD = "setPrompt"
    const val TRIPLE_TOAST_METHOD = "getToast"

    /** UP 关注引导气泡入口：静态、2 参、void 的方法（混淆签名会变，按「名字+形状」匹配）。 */
    const val FOLLOW_POPUP_CLASS = "com.bilibili.playerbizcommonv2.widget.popup.FollowPopupUtil"
    const val FOLLOW_POPUP_METHOD = "b"

    /** 互动弹幕投票面板的数据入口。 */
    const val VOTE_WIDGET_CLASS = "com.bilibili.playerbizcommonv2.danmaku.command.InteractDanmakuListWidget"
    const val VOTE_SET_DATA_METHOD = "setData"

    // ------------------------------------------------------------ 首页不自动刷新

    /**
     * feed 加载入口**按结构匹配**（任意「第 3 参类型是 PegasusFlush」的方法，跨版本稳定，
     * 6.3.0=z0 / 6.4.0=y0 / 6.5.0=x0），不依赖方法名；这两个真名类是结构匹配的锚点。
     */
    const val PEGASUS_VM_CLASS = "com.bilibili.pegasus.vm.PegasusViewModel"
    const val PEGASUS_FLUSH_CLASS = "com.bilibili.pegasus.data.request.PegasusFlush"

    // ------------------------------------------------------------ 分享到 QQ

    /** 6.4.0+ 宿主特征标记类（仅探测存在性，不 hook）。 */
    const val HOME_APP_BAR_MARKER_CLASS = "tv.danmaku.bili.home.widget.top.HomeAppBarLayout"

    /** 分享渠道 bean（真名，服务端下发渠道的载体）。 */
    const val SHARE_CHANNELS_CLASS = "com.bilibili.lib.sharewrapper.online.api.ShareChannels"
    const val SHARE_CHANNEL_ITEM_CLASS = "com.bilibili.lib.sharewrapper.online.api.ShareChannels\$ChannelItem"
    const val SHARE_ABOVE_CHANNELS_GETTER = "getAboveChannels"

    // ------------------------------------------------------------ IP 属地

    /** KMP moss 发送入口（混淆名）：标记评论 RPC 的 ThreadLocal scope。 */
    val MOSS_SCOPE_ENTRY_CLASSES = listOf("ip1.h")
    val MOSS_SCOPE_ENTRY_METHODS = listOf("a")

    /**
     * KMP 头提供者兜底候选（混淆名，随构建漂移：6.3.0=up1.a / 6.4.0+=kr1.a；
     * 6.5.0 起变接口+抽象中转+5 个具体提供者，无单点，形状校验会让它安静跳过）。
     */
    val KMP_HEADER_PROVIDER_CLASSES = listOf("kr1.a", "up1.a")

    /** moss 公共头入口（真名，6.3.0-6.6.0 未漂移）：`b(2 参)`。 */
    const val MOSS_COMMON_HEADERS_CLASS = "kntr.base.moss.ignet.impl.header.b"
    const val MOSS_COMMON_HEADERS_METHOD = "b"

    /** gRPC 二进制头唯一写入口（真名）：`f(String, byte[])`。 */
    const val MOSS_GRPC_BIN_WRITE_CLASS = "kntr.base.moss.ignet.impl.grpc.c"
    const val MOSS_GRPC_BIN_WRITE_METHOD = "f"

    /** REST/旧 moss 身份 provider 候选（混淆名；6.3.0=mq0.a，6.4.0 起迁 oq0.a，方法同名 e/d）。 */
    val IDENTITY_PROVIDER_CLASSES = listOf("mq0.a", "oq0.a")
    val IDENTITY_PROVIDER_METHODS = listOf("e", "d")

    /** REST 评论拦截器候选（混淆名；6.3.0=Aq0.a，6.4.0 起=Cq0.a，`intercept(1 参)`）。 */
    val REST_INTERCEPTOR_CLASSES = listOf("Aq0.a", "Cq0.a")

    /** okretro REST 公共参数基类（混淆名；6.4.0 移除，靠 Cq0.a 覆盖）。 */
    const val REST_PARAMS_CLASS = "XA0.a"
    const val REST_PARAMS_TO_URL_METHOD = "addCommonParamToUrl"
    const val REST_PARAMS_MAP_METHOD = "addCommonParam"

    /** 空间页专属 REST 参数拦截器（真名，天然定域：只有空间请求经过它）。 */
    val SPACE_REST_PARAM_CLASSES = listOf("com.bilibili.app.comm.list.common.api.e")

    /** 空间页 UI 定域窗口的 Activity 候选（Local=6.4.0+ 实际页面，在前）。 */
    val SPACE_UI_ACTIVITY_CLASSES = listOf(
        "com.bilibili.app.authorspace.local.LocalAuthorSpaceActivity",
        "com.bilibili.app.authorspace.ui.AuthorSpaceActivity",
    )

    /**
     * gRPC 方法描述符字段的**类型名提示**（字段挂在 MossInterceptor$e.b，类型随构建漂移：
     * 6.3.0=jp1.g / 6.4.0=Zq1.g / 6.5.0=kr1.g / 6.6.0=xr1.g；服务名兜底字段同理为 *.k）。
     * 顺序即尝试顺序。
     */
    val MOSS_DESCRIPTOR_G_TYPE_HINTS = listOf("kr1.g", "Zq1.g", "jp1.g", "xr1.g")
    val MOSS_DESCRIPTOR_K_TYPE_HINTS = listOf("kr1.k", "Zq1.k", "jp1.k", "xr1.k")

    // ------------------------------------------------------------ 解锁番剧（U1 起，见 docs/UNLOCK_PLAN.md）

    // 播放链路 6.6.0 已全 gRPC 化（moss/protobuf），旧 REST playurl 端点不存在。
    // 本段全部是 bapis/**真名类**（protobuf 族宿主不混淆），跨版本稳定性远好于混淆短名；
    // 实证见 docs/UNLOCK_FEASIBILITY.md §2.1（索引逐条验证，2026-09-30）。

    /** 播放器聚合 moss 服务：`playViewUnite(req)` 是播放地址的唯一入口。 */
    const val PLAYER_MOSS_CLASS = "com.bapis.bilibili.app.playerunite.v1.PlayerMoss"
    val PLAY_VIEW_UNITE_METHODS = listOf("playViewUnite", "executePlayViewUnite")
    const val PLAY_VIEW_UNITE_REQ_CLASS = "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq"
    const val PLAY_VIEW_UNITE_REPLY_CLASS = "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReply"

    /** 选集面板 moss 服务（2026-10-02 jadx 实证：pgc.gateway.view.v1.ViewMoss）。 */
    const val VIEW_MOSS_CLASS = "com.bapis.bilibili.pgc.gateway.view.v1.ViewMoss"
    val SEASON_MOSS_METHODS = listOf(
        "seasonSections", "executeSeasonSections",
        "pageSectionEpisodes", "executePageSectionEpisodes",
        "seasonSectionsForCache", "executeSeasonSectionsForCache",
    )
    const val SEASON_SECTIONS_REPLY_CLASS =
        "com.bapis.bilibili.pgc.gateway.view.v1.SeasonSectionsReply"
    const val PAGE_SECTION_EPISODES_REPLY_CLASS =
        "com.bapis.bilibili.pgc.gateway.view.v1.PageSectionEpisodesReply"

    /** 详情页主数据服务（viewunite）：选集面板 tab 注入目标（2026-10-04 实证）。 */
    const val VIEW_UNITE_MOSS_CLASS = "com.bapis.bilibili.app.viewunite.v1.ViewMoss"
    const val VIEW_UNITE_REPLY_CLASS = "com.bapis.bilibili.app.viewunite.v1.ViewReply"

    /** 响应重构涉及的内层类（包名以 dex 索引为准：playershared 独立成包，Stream 系在 playurl.v1）。 */
    const val VOD_INFO_CLASS = "com.bapis.bilibili.playershared.VodInfo"
    const val PLAY_ARC_CLASS = "com.bapis.bilibili.playershared.PlayArc"
    const val PLAY_ARC_CONF_CLASS = "com.bapis.bilibili.playershared.PlayArcConf"
    const val ARC_CONF_CLASS = "com.bapis.bilibili.app.playurl.v1.ArcConf"
    const val DASH_VIDEO_CLASS = "com.bapis.bilibili.playershared.DashVideo"
    const val DASH_ITEM_CLASS = "com.bapis.bilibili.playershared.DashItem"
    const val STREAM_CLASS = "com.bapis.bilibili.playershared.Stream"
    const val PLAY_VIEW_REPLY_CLASS = "com.bapis.bilibili.pgc.gateway.player.v2.PlayViewReply"
    const val PGC_VIEW_INFO_CLASS = "com.bapis.bilibili.pgc.gateway.player.v2.ViewInfo"
    const val PGC_BUSINESS_INFO_CLASS = "com.bapis.bilibili.pgc.gateway.player.v2.PlayViewBusinessInfo"

    /** 借宿主签名：`LibBili` 的静态 `(Map) -> SignedQuery` 方法（方法名按签名形状解析）。 */
    const val LIB_BILI_CLASS = "com.bilibili.nativelibrary.LibBili"
    const val SIGNED_QUERY_CLASS = "com.bilibili.nativelibrary.SignedQuery"

    /**
     * gripper 账户门面实现（dex 索引实证：实现 GAccount.getAccessKey）。
     * 解锁线运行时令牌的捕获点：hook 其 getAccessKey()，App 任何鉴权请求都会经过。
     */
    const val GRIPPER_ACCOUNT_CLASS = "com.bilibili.gripper.container.account.d"
    const val GRIPPER_GET_ACCESS_KEY = "getAccessKey"
}
