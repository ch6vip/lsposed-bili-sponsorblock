package com.ctf.bilisb.settings

/**
 * 设置键与默认值定义。
 *
 * 存储架构:LSPosed 模块进程(Hook 运行在目标宿主进程里)和设置 Activity(在模块 APK 里)
 * 是不同进程。模块 APK 的 SharedPreferences 是**权威存储**(由 [SettingsProvider] 暴露),
 * JSON 镜像文件只是兜底副本。读取端([ModuleSettings])走三级 fallback:
 *   1) ContentProvider IPC (权威存储,模块进程存活时)
 *   2) JSON 镜像文件 (模块进程已退出/被系统拦截时)
 *   3) 默认值
 */
object SettingsKeys {
    const val PREFS_NAME = "sponsorblock_settings"
    const val MIRROR_FILE = "sponsorblock_settings.json"

    // 本地 ↔ 权威存储的同步状态标记(属于内部元数据,不是用户设置)
    // KEY_LOCAL_DIRTY: 本地 prefs 有还没确认推送成功的改动 —— 为 true 时不能用权威快照覆盖本地。
    const val KEY_LOCAL_DIRTY = "local_dirty"

    // 总开关
    const val ENABLED = "enabled"
    const val AUTO_SKIP = "auto_skip"

    // 跳过策略
    // manual_skip: 开启后不自动跳过,改为在片段内显示"跳过"按钮,由用户点按跳过。
    const val MANUAL_SKIP = "manual_skip"
    // min_skip_duration: 最小片段时长(秒,可带小数)。短于此值的片段不跳过/不显示按钮。"0" = 不过滤。
    const val MIN_SKIP_DURATION = "min_skip_duration"
    // mute_segments: 对 actionType=mute 的片段静音(而非跳过)。默认关闭(对齐原 APK 未实现 mute)。
    const val MUTE_SEGMENTS = "mute_segments"
    // skip_countdown: 自动跳过倒计时(秒)。>0 时进入片段先显示"N秒后跳过 [取消]",倒计时结束才跳。"0" = 立即跳。
    const val SKIP_COUNTDOWN = "skip_countdown"

    // 服务器
    const val SERVER_ADDRESS = "server_address"

    /** 默认服务器引用 SponsorBlockConfig 的单一来源,避免两处字面量漂移。 */
    const val DEFAULT_SERVER = com.ctf.bilisb.model.SponsorBlockConfig.DEFAULT_SERVER_ADDRESS
    const val CACHE_TTL_MINUTES = "cache_ttl_minutes"
    const val DEFAULT_CACHE_TTL_MINUTES = "60"
    // 数值字段合法区间:入参(IPC / JSON 镜像)不可信,统一 clamp,例如 cache_ttl_minutes 传 1e38。
    /** 缓存 TTL 上限(分钟,7 天)。 */
    const val MAX_CACHE_TTL_MINUTES = 10_080
    /** 最小片段时长上限(秒,1 小时)。 */
    const val MAX_MIN_SKIP_DURATION_SECONDS = 3_600f
    /** 自动跳过倒计时上限(秒,10 分钟)。 */
    const val MAX_SKIP_COUNTDOWN_SECONDS = 600f
    /** 镜像 / 统计 JSON 读取上限,超出视为损坏。 */
    const val MAX_LOCAL_FILE_BYTES = 256 * 1024

    // 解锁番剧（默认关闭；服务器由用户自配，风险说明见 docs/UNLOCK_PLAN.md 与设置页提示）
    const val UNLOCK_ENABLED = "unlock_enabled"
    const val UNLOCK_SERVER_URL = "unlock_server_url"
    const val UNLOCK_SERVER_ACCESS_KEY = "unlock_server_access_key"
    const val UNLOCK_CACHE = "unlock_cache"
    const val UNLOCK_SEARCH = "unlock_search"
    const val UNLOCK_UPOS_HOST = "unlock_upos_host"
    // 漫游区域（cn/hk/tw/th）：决定服务器转发到哪个上游。必须进 Codec 通道，
    // 否则设置同步会用不含此键的快照重写 JSON 镜像，UnlockConfig 只好回落 "cn"。
    const val UNLOCK_SERVER_AREA = "unlock_server_area"
    // 开发专用：命中该 ep_id 的正常 PGC 请求被强制按受限处理（不进设置 UI）。
    // 同理必须在 Codec 通道里，否则镜像同步会把它抹掉，真机 forceTest 失效。
    const val UNLOCK_TEST_EPID = "unlock_test_epid"

    // 提交配置
    const val USER_ID = "user_id"
    const val DEFAULT_SUBMIT_CATEGORY = "default_submit_category"
    const val DEFAULT_SUBMIT_CATEGORY_VALUE = "sponsor"

    // 类别开关 (每个类别一个 bool)
    const val CAT_SPONSOR = "cat_sponsor"
    const val CAT_SELFPROMO = "cat_selfpromo"
    const val CAT_INTERACTION = "cat_interaction"
    const val CAT_INTRO = "cat_intro"
    const val CAT_OUTRO = "cat_outro"
    const val CAT_PREVIEW = "cat_preview"
    const val CAT_MUSIC_OFFTOPIC = "cat_music_offtopic"
    const val CAT_FILLER = "cat_filler"
    const val CAT_POI_HIGHLIGHT = "cat_poi_highlight"

    // B 站增强功能(移植自 BiliTamer,MIT):全部默认关闭,按需开启
    const val ENHANCE_IP_LOCATION = "enhance_ip_location"          // 评论/主页 IP 属地
    const val ENHANCE_HIDE_TRIPLE = "enhance_hide_triple"          // 隐藏一键三连提示
    const val ENHANCE_HIDE_UP_PROMPT = "enhance_hide_up_prompt"    // 隐藏 UP 提示(关注引导气泡)
    const val ENHANCE_HIDE_VOTE = "enhance_hide_vote"              // 隐藏投票/互动弹幕
    const val ENHANCE_NO_AUTO_REFRESH = "enhance_no_auto_refresh"  // 首页不自动刷新
    const val ENHANCE_SHARE_QQ = "enhance_share_qq"                // 分享面板补回「分享到 QQ」

    /** 增强功能开关的 key 集合(Codec 字段映射用)。 */
    val ENHANCE_KEYS = listOf(
        ENHANCE_IP_LOCATION, ENHANCE_HIDE_TRIPLE, ENHANCE_HIDE_UP_PROMPT, ENHANCE_HIDE_VOTE,
        ENHANCE_NO_AUTO_REFRESH, ENHANCE_SHARE_QQ,
    )

    // UI 开关
    const val SHOW_TOAST = "show_toast"
    const val SHOW_SEEKBAR_MARKER = "show_seekbar_marker"
    const val SHOW_TIME_DEDUCTION = "show_time_deduction"

    // 是否记录/展示跳过统计（播放器面板与设置页的「跳过次数统计」开关）
    const val SHOW_SKIP_STATS = "show_skip_stats"
    const val SHOW_SUBMIT_BUTTON = "show_submit_button"

    // 分类标记颜色:key 为 "color_<category>",值为 "#RRGGBB" hex 字符串。
    const val COLOR_PREFIX = "color_"
    fun colorKey(category: String): String = COLOR_PREFIX + category

    /** 类别 → 默认标记颜色（hex）。**单一事实来源是 [com.ctf.bilisb.model.SponsorCategories]**，
     *  这里只是它的「设置存储形态」（`#RRGGBB` 字符串），不要再另写一份字面量。 */
    val CATEGORY_COLOR_DEFAULTS: Map<String, String> = com.ctf.bilisb.model.SponsorCategories.defaultColorHex

    /** 类别 key → SponsorBlock category 字符串。构成与顺序同样由 SponsorCategories 定，
     *  这里只把 category 字面量换成对应的设置键。 */
    val CATEGORY_MAP: Map<String, String> = mapOf(
        CAT_SPONSOR to "sponsor",
        CAT_SELFPROMO to "selfpromo",
        CAT_INTERACTION to "interaction",
        CAT_INTRO to "intro",
        CAT_OUTRO to "outro",
        CAT_PREVIEW to "preview",
        CAT_MUSIC_OFFTOPIC to "music_offtopic",
        CAT_FILLER to "filler",
        CAT_POI_HIGHLIGHT to com.ctf.bilisb.model.SponsorCategories.POI_HIGHLIGHT,
    )

    /**
     * 一致性守卫（类加载即执行）：`CATEGORY_MAP` / 颜色默认值必须与
     * [com.ctf.bilisb.model.SponsorCategories] 的分类集合完全一致。
     * 两边任一处漏改都会在这里直接抛错，而不是等到「某个分类的开关静默失效」才被发现。
     */
    init {
        // 注意方向：CATEGORY_MAP 是「设置键 → category」，元数据给的是「category → 设置键」。
        val expected = com.ctf.bilisb.model.SponsorCategories.settingsKeysInverted
        require(CATEGORY_MAP == expected) {
            "SettingsKeys.CATEGORY_MAP 与 SponsorCategories 不一致:\n" +
                "  CATEGORY_MAP       = $CATEGORY_MAP\n" +
                "  SponsorCategories  = $expected"
        }
        val declaredOrder = com.ctf.bilisb.model.SponsorCategories.ordered.map { it.id }
        require(CATEGORY_COLOR_DEFAULTS.keys.toList() == declaredOrder) {
            "默认颜色表与 SponsorCategories 的分类列表不一致:" +
                " ${CATEGORY_COLOR_DEFAULTS.keys} vs $declaredOrder"
        }
    }

}
