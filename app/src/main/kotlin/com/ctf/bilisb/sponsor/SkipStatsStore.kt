package com.ctf.bilisb.sponsor

import android.util.Log
import com.ctf.bilisb.host.HostTargets
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 跳过统计存储:累计「已跳过片段数」与「节省时长」,按分类细分。
 *
 * 进程内单例(运行在目标宿主进程,见 [HostTargets.HOST_PACKAGE]):
 *   - controller 在三处跳过点(立即 / 倒计时结束 / 手动点按)调用 [record]。
 *   - controller 每次进播放页都会被重建,所以计数不能只放在 controller 实例里 ——
 *     这里用进程级单例保证跨视频累计,再持久化到宿主数据目录 JSON 保证跨进程重启存活。
 *   - 设置入口对话框(SponsorBlockSettingDialog)同样跑在宿主进程,直接读这个单例显示。
 *
 * 时长口径:按「片段长度」(endMs-startMs)累计,与其它 SponsorBlock 客户端一致,
 * 而非「从当前位置到片段尾」的实际跳跃量(倒计时模式下二者会差几秒)。
 *
 * ## 线程模型(首次加载不再卡主线程)
 *
 * 类初始化时就在 [io] 线程上异步加载一次磁盘数据。此后:
 *   - [snapshot] / [record] 首先调用 [ensureLoaded]:如果后台加载还没完成,就**等**它
 *     (最多 [LOAD_WAIT_MS]),而不是自己在主线程读盘;后台加载已经完成时只是一次判断,
 *     语义与原来的「首次访问同步加载」完全一致,只是读盘发生在线程池里。
 *   - 后台加载因为线程池被占用/关闭而没跑起来时,[ensureLoaded] 兜底自己同步加载一次
 *     (老行为),保证统计不会静默丢数据。
 */
object SkipStatsStore {
    private const val TAG = "SkipStatsStore"

    // 与 settings 镜像同目录,宿主进程可写自己的数据目录。
    // 6.5.0 目标宿主是 com.bilibili.app.in；旧包目录保留兜底（读写都按候选顺序尝试）。
    private val candidates = HostTargets.HOST_DATA_DIRS.map { File(it, "sponsorblock_stats.json") }

    /** 原子写入用的临时文件后缀(同目录 rename 才是原子的)。 */
    private const val TMP_SUFFIX = ".tmp"

    /** 损坏文件的备份后缀(JSON 解析失败时保留现场,方便排查)。 */
    private const val BAK_SUFFIX = ".bak"

    /** snapshot/record 等待后台加载的最长时间。 */
    private const val LOAD_WAIT_MS = 2_000L

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()

    /** 首次加载只做一次的闸门。 */
    private val loadLatch = CountDownLatch(1)
    private var loaded = false
    private var loadStarted = false

    private var totalCount = 0L
    private var totalDurationMs = 0L
    private val perCategoryCount = LinkedHashMap<String, Long>()
    private val perCategoryDurationMs = HashMap<String, Long>()

    init {
        // 类初始化时就异步读盘:UI 第一次打开统计对话框前数据通常已经就绪。
        io.execute { ensureLoaded() }
    }

    data class CategoryStat(val count: Long, val durationMs: Long)

    data class Snapshot(
        val totalCount: Long,
        val totalDurationMs: Long,
        val perCategory: Map<String, CategoryStat>,
    )

    /** 记录一次成功跳过。durationMs<=0 直接忽略。 */
    fun record(category: String, durationMs: Long) {
        if (durationMs <= 0) return
        ensureLoaded()
        synchronized(lock) {
            totalCount += 1
            totalDurationMs += durationMs
            perCategoryCount[category] = (perCategoryCount[category] ?: 0L) + 1
            perCategoryDurationMs[category] = (perCategoryDurationMs[category] ?: 0L) + durationMs
        }
        persistAsync()
    }

    fun snapshot(): Snapshot {
        ensureLoaded()
        return synchronized(lock) {
            val per = LinkedHashMap<String, CategoryStat>()
            for ((cat, count) in perCategoryCount) {
                per[cat] = CategoryStat(count, perCategoryDurationMs[cat] ?: 0L)
            }
            Snapshot(totalCount, totalDurationMs, per)
        }
    }

    fun reset() {
        ensureLoaded()
        synchronized(lock) {
            // loaded 已被 ensureLoaded 置位,避免重置后又被磁盘旧值覆盖。
            totalCount = 0
            totalDurationMs = 0
            perCategoryCount.clear()
            perCategoryDurationMs.clear()
        }
        persistAsync()
    }

    /** 进程内首次访问时从磁盘加载一次;之后以内存为准。 */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            loadStarted = true
        }
        // 后台 preload 已经在跑:等它读完,而不是主线程再读一遍。
        if (!loadLatch.await(LOAD_WAIT_MS, TimeUnit.MILLISECONDS)) {
            // 超时(线程池被占用/关闭):兜底自己加载,保证统计不丢。
            loadFromDisk()
        }
    }

    /** 真正读盘。可能被后台 preload 或 [ensureLoaded] 兜底调用,重复调用是幂等的。 */
    private fun loadFromDisk() {
        val snapshot = readFromDisk()
        synchronized(lock) {
            if (!loaded) {
                totalCount = snapshot.totalCount
                totalDurationMs = snapshot.totalDurationMs
                perCategoryCount.putAll(snapshot.perCategoryCount)
                perCategoryDurationMs.putAll(snapshot.perCategoryDurationMs)
                loaded = true
            }
        }
        loadLatch.countDown()
    }

    private class DiskSnapshot(
        val totalCount: Long,
        val totalDurationMs: Long,
        val perCategoryCount: Map<String, Long>,
        val perCategoryDurationMs: Map<String, Long>,
    )

    private fun readFromDisk(): DiskSnapshot {
        for (file in candidates) {
            if (!file.exists() || !file.canRead()) continue
            val parsed = runCatching { parse(file) }
            if (parsed.isSuccess) return parsed.getOrThrow()

            // 解析失败:打 warn 并保留现场(.bak),绝不静默归零。
            Log.w(TAG, "stats file corrupted, keeping backup: ${file.absolutePath}", parsed.exceptionOrNull())
            backupCorruptedFile(file)
        }
        return DiskSnapshot(0L, 0L, emptyMap(), emptyMap())
    }

    private fun parse(file: File): DiskSnapshot {
        val json = JSONObject(file.readText())
        val counts = HashMap<String, Long>()
        val durations = HashMap<String, Long>()
        json.optJSONObject("perCategory")?.let { per ->
            val keys = per.keys()
            while (keys.hasNext()) {
                val cat = keys.next()
                val obj = per.optJSONObject(cat) ?: continue
                counts[cat] = obj.optLong("count", 0L)
                durations[cat] = obj.optLong("durationMs", 0L)
            }
        }
        return DiskSnapshot(
            totalCount = json.optLong("totalCount", 0L),
            totalDurationMs = json.optLong("totalDurationMs", 0L),
            perCategoryCount = counts,
            perCategoryDurationMs = durations,
        )
    }

    /** 把损坏文件改名成 `.bak` 保留现场;改名失败则退化为复制(仍保留原文件)。 */
    private fun backupCorruptedFile(file: File) {
        val backup = File(file.parentFile, file.name + BAK_SUFFIX)
        runCatching {
            backup.delete()
            if (!file.renameTo(backup)) {
                file.copyTo(backup, overwrite = true)
            }
        }.onFailure {
            Log.w(TAG, "failed to back up corrupted stats file: ${file.absolutePath}", it)
        }
    }

    private fun persistAsync() {
        val content = synchronized(lock) { serialize() }
        runCatching {
            io.execute { writeAtomically(content) }
        }.onFailure {
            // 线程池已关闭(进程退出中):退化为同步写,避免丢最后一次统计。
            writeAtomically(content)
        }
    }

    /**
     * 原子写入:先写同目录 `.tmp`,再 `renameTo` 覆盖正式文件。
     *
     * 直接 `writeText` 覆盖时,进程若在写到一半被杀,留下的半截 JSON 会让下次启动解析失败、
     * 统计整个归零。rename 在同一文件系统内是原子的,失败时退回直接写并打日志。
     */
    private fun writeAtomically(content: String) {
        for (file in candidates) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
                tmp.writeText(content)
                if (!tmp.renameTo(file)) {
                    // 某些机型/目录不允许 rename 覆盖已有文件:回退直接写。
                    file.writeText(content)
                    tmp.delete()
                    Log.w(TAG, "atomic rename failed, fell back to direct write: ${file.absolutePath}")
                }
            }.onFailure {
                Log.w(TAG, "failed to persist stats to ${file.absolutePath}", it)
            }
        }
    }

    private fun serialize(): String {
        val json = JSONObject()
        json.put("totalCount", totalCount)
        json.put("totalDurationMs", totalDurationMs)
        val per = JSONObject()
        for ((cat, count) in perCategoryCount) {
            per.put(cat, JSONObject().apply {
                put("count", count)
                put("durationMs", perCategoryDurationMs[cat] ?: 0L)
            })
        }
        json.put("perCategory", per)
        return json.toString(2)
    }
}
