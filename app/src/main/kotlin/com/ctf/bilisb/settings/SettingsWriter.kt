package com.ctf.bilisb.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ctf.bilisb.host.HostTargets
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    /** 已 close 标记:防止重复 unregister/shutdown。 */
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (internalWrite.get() == true) return@OnSharedPreferenceChangeListener
        // apply() 在非主线程提交时会把监听 post 到主线程，ThreadLocal 挡不住；
        // dirty 标记本身的翻转不能再当成用户改动，否则会 IPC 空转。
        if (key == SettingsKeys.KEY_LOCAL_DIRTY) return@OnSharedPreferenceChangeListener
        onLocalSettingsChanged()
    }

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
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // 初始化时投递一次镜像(同样不在构造线程写文件)
        ioExecutor.execute { mirrorToFile() }
    }

    val sharedPreferences: SharedPreferences get() = prefs

    fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default

    /**
     * 释放资源:反注册监听 + 停掉 IO 线程。幂等。
     *
     * 弹窗/设置页持有方在关闭时调用;不调用的话每开一次弹窗就泄漏一条线程 + 一条监听器,
     * 同一设置改动会被 N 个存活监听器各触发一次镜像写与 provider 推送。
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        ioExecutor.shutdownNow()
    }

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
        // 进程级锁:同一进程可能有多个 writer 实例(弹窗 writer / hook 侧缓存 writer),
        // 各自的 IO 线程并发写同一个固定名 `.tmp` 再 rename,会把撕裂的 JSON 发布成正式镜像。
        synchronized(MIRROR_WRITE_LOCK) {
            // 目标按 canonical path 去重:/data/data/X 与 /data/user/0/X 是同一目录,重复写纯浪费。
            val seen = HashSet<String>()
            val ordered = mirrorTargets.filter { target ->
                seen.add(runCatching { target.canonicalPath }.getOrDefault(target.absolutePath))
            }
            if (ordered.isEmpty()) return
            // 第一个目标(模块进程=权威兜底镜像;宿主进程=自身 filesDir)始终写;
            // 其余(宿主数据目录,读取端按顺序取第一个可读)组内首个写成功即停,
            // 不再陪跑不可写的 legacy 目录(每次两条带堆栈的 warn)。
            writeMirrorAtomically(ordered.first(), content)
            for (target in ordered.drop(1)) {
                if (writeMirrorAtomically(target, content)) break
            }
        }
    }

    /**
     * 原子写镜像:先写同目录的 `<name>.tmp` 再 renameTo(target)(同目录 rename 是原子的),
     * 避免 Hook 端 tryFileFallback 读到写了一半的 JSON。rename 失败才退回直接写。
     * @return 是否写成功(供调用方「首个成功即停」)。
     */
    private fun writeMirrorAtomically(target: File, content: String): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            target.parentFile?.mkdirs()
            tmp.writeText(content)
            if (tmp.renameTo(target)) return true
            Log.w(TAG, "mirror rename failed, fallback to direct write: ${target.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "mirror tmp write failed: ${target.absolutePath}: ${e.message}")
        }
        val direct = runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content)
            true
        }.onFailure { Log.w(TAG, "mirror write failed: ${target.absolutePath}: ${it.message}") }.getOrDefault(false)
        runCatching { tmp.delete() }
        return direct
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

        /** 镜像写进程级锁:同一进程的多个 writer 实例串行化 `.tmp` 写入(见 [mirrorToFile])。 */
        val MIRROR_WRITE_LOCK = Any()
    }
}
