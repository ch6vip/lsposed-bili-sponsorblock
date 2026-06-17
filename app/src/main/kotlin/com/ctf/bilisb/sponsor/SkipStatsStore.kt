package com.ctf.bilisb.sponsor

import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 跳过统计存储:累计「已跳过片段数」与「节省时长」,按分类细分。
 *
 * 进程内单例(运行在宿主 tv.danmaku.bili 进程):
 *   - controller 在三处跳过点(立即 / 倒计时结束 / 手动点按)调用 [record]。
 *   - controller 每次进播放页都会被重建,所以计数不能只放在 controller 实例里 ——
 *     这里用进程级单例保证跨视频累计,再持久化到宿主数据目录 JSON 保证跨进程重启存活。
 *   - 设置入口对话框(SponsorBlockSettingDialog)同样跑在宿主进程,直接读这个单例显示。
 *
 * 时长口径:按「片段长度」(endMs-startMs)累计,与其它 SponsorBlock 客户端一致,
 * 而非「从当前位置到片段尾」的实际跳跃量(倒计时模式下二者会差几秒)。
 */
object SkipStatsStore {
    // 与 settings 镜像同目录,宿主进程可写自己的数据目录。
    private val candidates = listOf(
        File("/data/data/tv.danmaku.bili/sponsorblock_stats.json"),
        File("/data/user/0/tv.danmaku.bili/sponsorblock_stats.json"),
    )

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()

    private var loaded = false
    private var totalCount = 0L
    private var totalDurationMs = 0L
    private val perCategoryCount = LinkedHashMap<String, Long>()
    private val perCategoryDurationMs = HashMap<String, Long>()

    data class CategoryStat(val count: Long, val durationMs: Long)

    data class Snapshot(
        val totalCount: Long,
        val totalDurationMs: Long,
        val perCategory: Map<String, CategoryStat>,
    )

    /** 记录一次成功跳过。durationMs<=0 直接忽略。 */
    fun record(category: String, durationMs: Long) {
        if (durationMs <= 0) return
        synchronized(lock) {
            ensureLoaded()
            totalCount += 1
            totalDurationMs += durationMs
            perCategoryCount[category] = (perCategoryCount[category] ?: 0L) + 1
            perCategoryDurationMs[category] = (perCategoryDurationMs[category] ?: 0L) + durationMs
        }
        persistAsync()
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        ensureLoaded()
        val per = LinkedHashMap<String, CategoryStat>()
        for ((cat, count) in perCategoryCount) {
            per[cat] = CategoryStat(count, perCategoryDurationMs[cat] ?: 0L)
        }
        Snapshot(totalCount, totalDurationMs, per)
    }

    fun reset() {
        synchronized(lock) {
            ensureLoaded() // 标记 loaded,避免重置后又被磁盘旧值覆盖
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
        loaded = true
        for (file in candidates) {
            val ok = runCatching {
                if (!file.exists() || !file.canRead()) return@runCatching false
                val json = JSONObject(file.readText())
                totalCount = json.optLong("totalCount", 0L)
                totalDurationMs = json.optLong("totalDurationMs", 0L)
                json.optJSONObject("perCategory")?.let { per ->
                    val keys = per.keys()
                    while (keys.hasNext()) {
                        val cat = keys.next()
                        val obj = per.optJSONObject(cat) ?: continue
                        perCategoryCount[cat] = obj.optLong("count", 0L)
                        perCategoryDurationMs[cat] = obj.optLong("durationMs", 0L)
                    }
                }
                true
            }.getOrDefault(false)
            if (ok) break
        }
    }

    private fun persistAsync() {
        val content = synchronized(lock) { serialize() }
        io.execute {
            for (file in candidates) {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }
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
