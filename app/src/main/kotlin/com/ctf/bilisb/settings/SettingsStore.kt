package com.ctf.bilisb.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.net.Uri
import android.util.Log
import com.ctf.bilisb.host.HostTargets
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 设置存储。
 *
 * 关键:LSPosed 模块进程(在目标宿主进程里)和设置 Activity(在模块 APK 里)
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

    /** 类别 → 默认标记颜色(hex)。与 ProgressMarkerPainter 内置配色一致。保序用于 UI 展示。 */
    val CATEGORY_COLOR_DEFAULTS: Map<String, String> = linkedMapOf(
        "sponsor" to "#00D200",         // 绿色
        "selfpromo" to "#FFFF00",       // 黄色
        "interaction" to "#AA00FF",     // 紫色
        "intro" to "#00FFFF",           // 青色
        "outro" to "#0064FF",           // 蓝色
        "preview" to "#FF8000",         // 橙色
        "music_offtopic" to "#FF00B4",  // 粉色
        "filler" to "#7F00FF",          // 深紫
        "poi_highlight" to "#FF1E1E",   // 红色
    )

    /** 类别 key → SponsorBlock category 字符串 */
    val CATEGORY_MAP = mapOf(
        CAT_SPONSOR to "sponsor",
        CAT_SELFPROMO to "selfpromo",
        CAT_INTERACTION to "interaction",
        CAT_INTRO to "intro",
        CAT_OUTRO to "outro",
        CAT_PREVIEW to "preview",
        CAT_MUSIC_OFFTOPIC to "music_offtopic",
        CAT_FILLER to "filler",
        CAT_POI_HIGHLIGHT to "poi_highlight",
    )

}

/**
 * 设置 Activity 端使用的写入器。
 *
 * 职责:
 *   1. 监听 SharedPreferences 变更 → 写 JSON 镜像文件 + 推送到权威存储(模块 APK 的 provider);
 *   2. 本地没有未同步改动时,用权威快照刷新本地 prefs(hydrate)。
 *
 * 线程模型:变更回调可能发生在宿主主线程上,所以监听体只做「标 dirty + 投递任务」,
 * 文件 IO 与同步 Binder IPC 一律交给单线程 [ioExecutor] 串行执行。
 */
class SettingsWriter(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)

    /** 模块 App 进程:它的 prefs 就是权威存储本身,不需要 hydrate。 */
    private val isModuleProcess = appContext.packageName == SettingsSyncBridge.MODULE_PACKAGE

    /** 自身或宿主进程的写入才需要推给 provider(保持原有语义)。 */
    private val syncToModule = isModuleProcess ||
        appContext.packageName == SettingsSyncBridge.HOST_PACKAGE

    private val mirrorTargets = mutableListOf<File>()

    /**
     * 单线程 IO 队列:镜像写文件(最多 5 个目标)和 provider 同步都离开调用线程。
     * 守护线程,进程退出不需要特殊处理(没落盘的镜像会在下次变更时重写)。
     */
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bilisb-settings-io").apply { isDaemon = true }
    }

    /**
     * 「内部写入」标记(按线程):apply() 会在写入线程上同步回调监听器,
     * 用它区分用户改动和我们自己写的 dirty / hydrate 标记,避免同步被自我触发。
     */
    private val internalWrite = ThreadLocal<Boolean>()

    init {
        hydrateFromCanonicalStoreIfNeeded()

        // 镜像位置 1: 模块自身 filesDir
        mirrorTargets.add(File(appContext.filesDir, SettingsKeys.MIRROR_FILE))

        // 镜像位置 2: 目标 App 数据目录 (Hook 端可直接读取)
        // 6.5.0 目标宿主是 com.bilibili.app.in；旧包目录保留作为兜底（见 HostTargets.HOST_DATA_DIRS）
        // 模块进程写宿主数据目录必然因沙箱失败,只会白跑 4 次失败的写 + 异常填栈,直接跳过。
        if (!isModuleProcess) {
            for (dir in HostTargets.HOST_DATA_DIRS) {
                mirrorTargets.add(File(dir, SettingsKeys.MIRROR_FILE))
            }
        }

        // 监听变更:只标 dirty + 投递任务,绝不在监听线程(宿主主线程)做文件 IO / IPC
        prefs.registerOnSharedPreferenceChangeListener { _, _ ->
            if (internalWrite.get() == true) return@registerOnSharedPreferenceChangeListener
            onLocalSettingsChanged()
        }
        // 初始化时投递一次镜像(同样不在构造线程写文件)
        ioExecutor.execute { mirrorToFile() }
    }

    val sharedPreferences: SharedPreferences get() = prefs

    fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default

    /** 用户(设置页/宿主内弹窗)改了设置:标 dirty 后把镜像与 provider 同步投递到 IO 队列。 */
    private fun onLocalSettingsChanged() {
        editInternal { putBoolean(SettingsKeys.KEY_LOCAL_DIRTY, true) }
        ioExecutor.execute {
            mirrorToFile()
            syncSnapshotToModule()
        }
    }

    /** 内部写入:不触发同步回调(见 [internalWrite])。 */
    private fun editInternal(block: SharedPreferences.Editor.() -> Unit) {
        internalWrite.set(true)
        try {
            val editor = prefs.edit()
            editor.block()
            editor.apply()
        } finally {
            internalWrite.set(false)
        }
    }

    /**
     * 用权威存储(模块 App 的 provider)回填本地 prefs。
     *
     * 只在「本地没有未确认推送的改动」时才覆盖:
     *   - 本地有未确认推送成功的改动([SettingsKeys.KEY_LOCAL_DIRTY])时跳过,以本地为准,
     *     否则用户刚改完的设置会被旧快照回滚(推送成功后标记会被清掉,那时权威快照里
     *     本来就包含这些改动,覆盖等价于刷新,安全);
     *   - 模块进程本身是权威存储的宿主,不需要回填。
     *
     * 注意:这里不能加「只 hydrate 一次」的短路 —— 宿主进程的 prefs 是本地副本,
     * 每次重新打开宿主内弹窗都要拿权威值刷新,否则会显示旧值并可能把旧值推回 provider。
     */
    private fun hydrateFromCanonicalStoreIfNeeded() {
        if (isModuleProcess) return
        if (prefs.getBoolean(SettingsKeys.KEY_LOCAL_DIRTY, false)) {
            Log.w(TAG, "hydrate skipped: local prefs has unsynced edits, keep local as source of truth")
            return
        }
        val snapshot = SettingsSyncBridge.readSnapshot(appContext) ?: return
        internalWrite.set(true)
        try {
            SettingsCodec.writeSnapshotToPreferences(prefs, snapshot)
        } finally {
            internalWrite.set(false)
        }
    }

    /** 把当前设置写成 JSON 镜像。 */
    private fun mirrorToFile() {
        val content = SettingsCodec.snapshotToJson(SettingsCodec.snapshotFromPreferences(prefs)).toString(2)
        for (target in mirrorTargets) {
            writeMirrorAtomically(target, content)
        }
    }

    /**
     * 原子写镜像:先写同目录的 `<name>.tmp` 再 renameTo(target)(同目录 rename 是原子的),
     * 避免 Hook 端 tryFileFallback 读到写了一半的 JSON。rename 失败才退回直接写。
     */
    private fun writeMirrorAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            target.parentFile?.mkdirs()
            tmp.writeText(content)
            if (tmp.renameTo(target)) return
            Log.w(TAG, "mirror rename failed, fallback to direct write: ${target.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "mirror tmp write failed: ${target.absolutePath}: ${e.message}")
        }
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content)
        }.onFailure { Log.w(TAG, "mirror write failed: ${target.absolutePath}: ${it.message}") }
        runCatching { tmp.delete() }
    }

    /**
     * 把本地设置推给权威存储(provider)。
     *
     * 模块进程也要推:模块 App 里改的设置只有进了 provider(= 模块 prefs)才会被 Hook 端读到。
     * 失败不能静默:保留 dirty 标记 + warn,后续 hydrate 一律以本地为准,避免用户改动被回滚。
     */
    private fun syncSnapshotToModule() {
        if (!syncToModule) return
        val snapshot = SettingsCodec.snapshotFromPreferences(prefs)
        // 同进程 provider call 会在调用线程上同步执行 provider 的 writeSnapshotToPreferences → apply(),
        // apply() 又会回调本 writer 的变更监听器 —— 必须打上 internalWrite 标记,否则同步自我触发死循环
        // (SharedPreferencesImpl 即使值相同也会回调监听器)。
        val marked = internalWrite.get() == true
        if (!marked) internalWrite.set(true)
        try {
            if (SettingsSyncBridge.writeSnapshot(appContext, snapshot)) {
                // 权威存储已接受:清掉 dirty,之后可以安全地用权威快照 hydrate
                editInternal { putBoolean(SettingsKeys.KEY_LOCAL_DIRTY, false) }
            } else {
                Log.w(TAG, "writeSnapshot failed, keep local prefs as source of truth")
            }
        } finally {
            if (!marked) internalWrite.set(false)
        }
    }

    private companion object {
        const val TAG = "SettingsWriter"
    }
}

object SettingsCodec {
    fun snapshotFromPreferences(prefs: SharedPreferences): SettingsSnapshot {
        return SettingsSnapshot(
            enabled = prefs.getBoolean(SettingsKeys.ENABLED, true),
            autoSkip = prefs.getBoolean(SettingsKeys.AUTO_SKIP, true),
            manualSkip = prefs.getBoolean(SettingsKeys.MANUAL_SKIP, false),
            muteSegments = prefs.getBoolean(SettingsKeys.MUTE_SEGMENTS, false),
            minSkipDurationSec = parseDuration(
                prefs.getString(SettingsKeys.MIN_SKIP_DURATION, "0"),
                SettingsKeys.MAX_MIN_SKIP_DURATION_SECONDS,
            ),
            skipCountdownSec = parseDuration(
                prefs.getString(SettingsKeys.SKIP_COUNTDOWN, "0"),
                SettingsKeys.MAX_SKIP_COUNTDOWN_SECONDS,
            ),
            serverAddress = prefs.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER)
                ?: SettingsKeys.DEFAULT_SERVER,
            cacheTtlMs = parseCacheTtlMs(prefs.getString(SettingsKeys.CACHE_TTL_MINUTES, SettingsKeys.DEFAULT_CACHE_TTL_MINUTES)),
            userId = prefs.getString(SettingsKeys.USER_ID, "") ?: "",
            defaultSubmitCategory = sanitizeCategory(
                prefs.getString(SettingsKeys.DEFAULT_SUBMIT_CATEGORY, SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE),
            ),
            enabledCategories = enabledCategoriesFromPrefs(prefs::getBoolean),
            showToast = prefs.getBoolean(SettingsKeys.SHOW_TOAST, true),
            showSeekbarMarker = prefs.getBoolean(SettingsKeys.SHOW_SEEKBAR_MARKER, true),
            showTimeDeduction = prefs.getBoolean(SettingsKeys.SHOW_TIME_DEDUCTION, true),
            showSkipStats = prefs.getBoolean(SettingsKeys.SHOW_SKIP_STATS, true),
            showSubmitButton = prefs.getBoolean(SettingsKeys.SHOW_SUBMIT_BUTTON, true),
            categoryColors = SettingsKeys.CATEGORY_COLOR_DEFAULTS.mapValues { (category, def) ->
                parseColor(prefs.getString(SettingsKeys.colorKey(category), def), def)
            },
        )
    }

    /**
     * 字段映射的唯一实现:Bundle / JSON / SharedPreferences 三条通道都折叠到这里。
     *
     * 为什么用 Map 中转:JVM 单测里 `android.os.Bundle` 全是 stub(没有 Robolectric),
     * 折叠之后「整对象等价」的往返测试可以在无 Android 环境下覆盖全部字段。
     * 数值统一用字符串(与 Bundle / JSON 的持久化形态一致),颜色统一 `#RRGGBB`(裁掉 alpha)。
     */
    fun snapshotToMap(snapshot: SettingsSnapshot): Map<String, Any> {
        return linkedMapOf<String, Any>().apply {
            put(SettingsKeys.ENABLED, snapshot.enabled)
            put(SettingsKeys.AUTO_SKIP, snapshot.autoSkip)
            put(SettingsKeys.MANUAL_SKIP, snapshot.manualSkip)
            put(SettingsKeys.MUTE_SEGMENTS, snapshot.muteSegments)
            put(SettingsKeys.MIN_SKIP_DURATION, snapshot.minSkipDurationSec.toString())
            put(SettingsKeys.SKIP_COUNTDOWN, snapshot.skipCountdownSec.toString())
            put(SettingsKeys.SERVER_ADDRESS, snapshot.serverAddress)
            put(SettingsKeys.CACHE_TTL_MINUTES, formatMinutes(snapshot.cacheTtlMs))
            put(SettingsKeys.USER_ID, snapshot.userId)
            put(SettingsKeys.DEFAULT_SUBMIT_CATEGORY, snapshot.defaultSubmitCategory)
            SettingsKeys.CATEGORY_MAP.forEach { (key, category) ->
                put(key, snapshot.enabledCategories.contains(category))
            }
            put(SettingsKeys.SHOW_TOAST, snapshot.showToast)
            put(SettingsKeys.SHOW_SEEKBAR_MARKER, snapshot.showSeekbarMarker)
            put(SettingsKeys.SHOW_TIME_DEDUCTION, snapshot.showTimeDeduction)
            put(SettingsKeys.SHOW_SKIP_STATS, snapshot.showSkipStats)
            put(SettingsKeys.SHOW_SUBMIT_BUTTON, snapshot.showSubmitButton)
            SettingsKeys.CATEGORY_COLOR_DEFAULTS.forEach { (category, def) ->
                put(SettingsKeys.colorKey(category), snapshot.categoryColors[category]?.let(::toHex) ?: def)
            }
        }
    }

    /**
     * 从 Map 还原快照。
     *
     * 字段级容错(JSON 镜像可能是半截内容或被外部改写):单个字段出错只影响该字段,
     * 退回它的默认值,其余字段照常解析。布尔/字符串都要求类型严格匹配,不做隐式强转
     * (`"enabled": 1` 这类脏数据不会被当成 true 用)。
     */
    fun snapshotFromMap(values: Map<String, Any?>): SettingsSnapshot {
        fun <T> field(key: String, default: T, cast: (Any) -> T?): T =
            runCatching { values[key]?.let(cast) }.getOrNull() ?: default

        fun bool(key: String, default: Boolean) = field(key, default) { it as? Boolean }
        fun str(key: String, default: String) = field(key, default) { it as? String }

        return SettingsSnapshot(
            enabled = bool(SettingsKeys.ENABLED, true),
            autoSkip = bool(SettingsKeys.AUTO_SKIP, true),
            manualSkip = bool(SettingsKeys.MANUAL_SKIP, false),
            muteSegments = bool(SettingsKeys.MUTE_SEGMENTS, false),
            minSkipDurationSec = parseDuration(
                str(SettingsKeys.MIN_SKIP_DURATION, "0"),
                SettingsKeys.MAX_MIN_SKIP_DURATION_SECONDS,
            ),
            skipCountdownSec = parseDuration(
                str(SettingsKeys.SKIP_COUNTDOWN, "0"),
                SettingsKeys.MAX_SKIP_COUNTDOWN_SECONDS,
            ),
            serverAddress = str(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER),
            cacheTtlMs = parseCacheTtlMs(str(SettingsKeys.CACHE_TTL_MINUTES, SettingsKeys.DEFAULT_CACHE_TTL_MINUTES)),
            userId = str(SettingsKeys.USER_ID, ""),
            defaultSubmitCategory = sanitizeCategory(
                str(SettingsKeys.DEFAULT_SUBMIT_CATEGORY, SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE),
            ),
            enabledCategories = enabledCategoriesFromPrefs(::bool),
            showToast = bool(SettingsKeys.SHOW_TOAST, true),
            showSeekbarMarker = bool(SettingsKeys.SHOW_SEEKBAR_MARKER, true),
            showTimeDeduction = bool(SettingsKeys.SHOW_TIME_DEDUCTION, true),
            showSkipStats = bool(SettingsKeys.SHOW_SKIP_STATS, true),
            showSubmitButton = bool(SettingsKeys.SHOW_SUBMIT_BUTTON, true),
            categoryColors = SettingsKeys.CATEGORY_COLOR_DEFAULTS.mapValues { (category, def) ->
                parseColor(str(SettingsKeys.colorKey(category), def), def)
            },
        )
    }

    fun snapshotFromBundle(bundle: Bundle): SettingsSnapshot {
        val values = HashMap<String, Any?>(bundle.size())
        bundle.keySet().forEach { key -> values[key] = bundle.get(key) }
        return snapshotFromMap(values)
    }

    fun snapshotFromJson(json: JSONObject): SettingsSnapshot {
        val values = HashMap<String, Any?>(json.length())
        json.keys().forEach { key -> values[key] = json.opt(key) }
        return snapshotFromMap(values)
    }

    fun snapshotToJson(snapshot: SettingsSnapshot): JSONObject {
        return JSONObject().apply {
            snapshotToMap(snapshot).forEach { (key, value) -> put(key, value) }
        }
    }

    fun snapshotToBundle(snapshot: SettingsSnapshot): Bundle {
        return Bundle().apply {
            snapshotToMap(snapshot).forEach { (key, value) ->
                // 与旧形态保持一致:数值以字符串写入 Bundle,避免跨进程 Bundle 类型漂移
                if (value is Boolean) putBoolean(key, value) else putString(key, value.toString())
            }
        }
    }

    fun writeSnapshotToPreferences(prefs: SharedPreferences, snapshot: SettingsSnapshot) {
        prefs.edit().apply {
            snapshotToMap(snapshot).forEach { (key, value) ->
                if (value is Boolean) putBoolean(key, value) else putString(key, value.toString())
            }
        }.apply()
    }

    fun defaultSnapshot(): SettingsSnapshot = SettingsSnapshot(
        enabled = true,
        autoSkip = true,
        manualSkip = false,
        muteSegments = false,
        minSkipDurationSec = 0f,
        skipCountdownSec = 0f,
        serverAddress = SettingsKeys.DEFAULT_SERVER,
        cacheTtlMs = 60L * 60_000L,
        userId = "",
        defaultSubmitCategory = SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE,
        enabledCategories = SettingsKeys.CATEGORY_MAP.values.toSet(),
        showToast = true,
        showSeekbarMarker = true,
        showTimeDeduction = true,
        showSkipStats = true,
        showSubmitButton = true,
        categoryColors = SettingsKeys.CATEGORY_COLOR_DEFAULTS.mapValues { (_, hex) -> parseColor(hex, "#808080") },
    )

    private fun enabledCategoriesFromPrefs(reader: (String, Boolean) -> Boolean): Set<String> {
        return SettingsKeys.CATEGORY_MAP.filter { (key, _) ->
            reader(key, true)
        }.values.toSet()
    }

    private fun parseColor(hex: String?, default: String): Int =
        parseHexColor(hex) ?: parseHexColor(default) ?: 0xFF808080.toInt()

    /** 时长解析:非数值/NaN/无穷一律按 0,并 clamp 到 [0, maxSeconds]。 */
    private fun parseDuration(raw: String?, maxSeconds: Float): Float {
        val value = raw?.trim()?.toFloatOrNull() ?: return 0f
        if (!value.isFinite()) return 0f
        return value.coerceIn(0f, maxSeconds)
    }

    /** 缓存 TTL 解析(分钟 → 毫秒):非法值退回 60 分钟,并 clamp 到 [0, MAX_CACHE_TTL_MINUTES]。 */
    private fun parseCacheTtlMs(raw: String?): Long {
        val parsed = raw?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }
        val minutes = parsed?.coerceIn(0f, SettingsKeys.MAX_CACHE_TTL_MINUTES.toFloat()) ?: 60f
        return (minutes * 60_000L).toLong()
    }

    private fun formatMinutes(cacheTtlMs: Long): String {
        val minutes = cacheTtlMs / 60_000.0
        return if (minutes % 1.0 == 0.0) {
            minutes.toLong().toString()
        } else {
            val raw = minutes.toString()
            raw.trimEnd('0').trimEnd('.')
        }
    }

    private fun sanitizeCategory(raw: String?): String {
        val category = raw?.trim().orEmpty()
        return if (category in com.ctf.bilisb.model.SponsorCategories.displayNames) {
            category
        } else {
            SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE
        }
    }

    private fun toHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    private fun parseHexColor(raw: String?): Int? {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        val value = normalized.removePrefix("#")
        return when (value.length) {
            6 -> value.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
            8 -> value.toLongOrNull(16)?.toInt()
            else -> null
        }
    }
}

object SettingsSyncBridge {
    const val MODULE_PACKAGE = "io.github.ch6vip.bilisb"

    /** 目标宿主包名（bilibili 6.5.0 国际版）。 */
    const val HOST_PACKAGE = HostTargets.HOST_PACKAGE
    const val AUTHORITY = "io.github.ch6vip.bilisb.settings"
    const val METHOD_GET_SETTINGS = "getSettings"
    const val METHOD_PUT_SETTINGS = "putSettings"
    const val METHOD_PUT_USER_ID = "putUserId"
    private const val EXTRA_JSON = "settings_json"
    private const val EXTRA_USER_ID = "user_id"
    private const val TAG = "SettingsSyncBridge"

    fun readSnapshot(context: Context): SettingsSnapshot? {
        return runCatching {
            val bundle = context.contentResolver.call(contentUri(), METHOD_GET_SETTINGS, null, null) ?: return null
            SettingsCodec.snapshotFromBundle(bundle)
        }.getOrElse {
            Log.w(TAG, "readSnapshot failed: ${it.message}")
            null
        }
    }

    fun writeSnapshot(context: Context, snapshot: SettingsSnapshot): Boolean {
        return runCatching {
            val extras = Bundle().apply {
                putString(EXTRA_JSON, SettingsCodec.snapshotToJson(snapshot).toString())
            }
            val result = context.contentResolver.call(contentUri(), METHOD_PUT_SETTINGS, null, extras)
            result?.getBoolean("ok", false) == true
        }.getOrElse {
            Log.w(TAG, "writeSnapshot failed: ${it.message}")
            false
        }
    }

    fun writeUserId(context: Context, userId: String): Boolean {
        return runCatching {
            val extras = Bundle().apply {
                putString(EXTRA_USER_ID, userId)
            }
            val result = context.contentResolver.call(contentUri(), METHOD_PUT_USER_ID, null, extras)
            result?.getBoolean("ok", false) == true
        }.getOrElse {
            Log.w(TAG, "writeUserId failed: ${it.message}")
            false
        }
    }

    private fun contentUri(): Uri = Uri.parse("content://$AUTHORITY")
}

object SettingsProviderAccess {
    /**
     * 调用方准入:只放行「自身 App」和「目标宿主」。
     *
     * 三个条件都要满足:
     *   1. callingPackage(非 null 时)必须在允许集合内;
     *   2. uid 解析出的包名里至少有一个在允许集合内;
     *   3. callingPackage 必须属于该 uid 解析出的包名 —— 否则调用方是在谎报包名
     *      (uid 属于宿主、包名却是别的 App),一律拒绝。
     */
    fun isAllowedCaller(
        callingPackage: String?,
        uidPackages: Array<String>?,
        selfPackage: String,
    ): Boolean {
        val allowedPackages = setOf(selfPackage, SettingsSyncBridge.HOST_PACKAGE)
        if (callingPackage != null && callingPackage !in allowedPackages) {
            return false
        }
        val packages = uidPackages ?: return false
        if (packages.none { it in allowedPackages }) {
            return false
        }
        if (callingPackage != null && callingPackage !in packages) {
            return false
        }
        return true
    }
}
