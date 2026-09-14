package com.ctf.bilisb.ui

import com.ctf.bilisb.model.SponsorSegment

/**
 * 进度条标记的几何计算。
 *
 * 这里**只做纯计算、不碰 android.graphics**，所以能直接在 JVM 单测里跑。
 * 之前这段逻辑内联在 [ProgressMarkerPainter] 里，`coerceIn(min, max)` 在
 * `min > max` 时会抛 IllegalArgumentException（片段起点超出 duration，或片段
 * 贴在轨道最右 1px 内），异常被上层 runCatching 静默吞掉，还会中断
 * `forEach` 导致该片段之后的标记**整帧消失**。现在改成任何非法输入都安全跳过。
 */
object MarkerGeometry {

    /**
     * 一个待绘制的标记区间（像素坐标，已夹在轨道内）。
     *
     * [isPoi] 为 true 时只画圆心：[xEnd] 与 [xStart] 相同，调用方按半径画圆。
     */
    data class MarkerRange(
        val category: String,
        val isPoi: Boolean,
        val xStart: Float,
        val xEnd: Float,
    )

    /**
     * 把片段映射成像素区间。
     *
     * @param boundsLeft  轨道左边界（px，含）
     * @param boundsRight 轨道右边界（px，含）
     * @param durationMs  视频总时长（ms）。<=0 视为时长未知，返回空列表而不是抛异常。
     */
    fun markerRanges(
        boundsLeft: Float,
        boundsRight: Float,
        durationMs: Long,
        segments: List<SponsorSegment>,
    ): List<MarkerRange> {
        // duration 未知时无法做时间→像素映射。宁可不画，也不要基于垃圾值画错。
        if (durationMs <= 0L || segments.isEmpty()) {
            return emptyList()
        }
        val width = boundsRight - boundsLeft
        if (width <= 0f) {
            return emptyList()
        }

        val pxPerMs = width / durationMs.toFloat()
        val result = ArrayList<MarkerRange>(segments.size)

        // 先按迭代序全量收集，再过滤：即使某个片段被跳过，也不影响其它片段（旧实现是中断 forEach）。
        for (segment in segments) {
            val range = rangeOf(segment, boundsLeft, boundsRight, pxPerMs) ?: continue
            result.add(range)
        }
        return result
    }

    private fun rangeOf(
        segment: SponsorSegment,
        left: Float,
        right: Float,
        pxPerMs: Float,
    ): MarkerRange? {
        val isPoi = isPoi(segment)
        // 起点先夹到轨道内。片段起点超出 duration 时 xStart 会落到 right，
        // 此时已没有可画宽度，直接跳过（旧实现正是在这一步让 coerceIn 抛异常）。
        val xStart = clamp(left + segment.startMs * pxPerMs, left, right)
        if (xStart >= right) {
            return null
        }

        if (isPoi) {
            // POI 只给圆心，绘制半径由调用方按轨道半高决定。
            return MarkerRange(segment.category, true, xStart, xStart)
        }

        // 逆序/零长区间（endMs < startMs，例如宿主给了脏数据）直接跳过。
        // 注意零长（endMs == startMs）不在这里丢：下面会按 1px 宽画出来。
        val xEnd = clamp(left + segment.endMs * pxPerMs, left, right)
        if (xEnd < xStart) {
            return null
        }
        // 至少 1px 宽（保证极短片段可见），但绝不越过右边界。
        // clamp 保证 xStart < right，所以 xStart + 1f 一定 > xStart >= left，min/max 不会反转。
        val safeEnd = maxOf(xEnd, xStart + 1f)
        return MarkerRange(segment.category, false, xStart, minOf(safeEnd, right))
    }

    /**
     * 是否作为 POI 圆点绘制。
     *
     * 对应 APK `an.d()`：`actionType == "poi"` 或分类为 highlight。
     * 这里的分类字面量与 [RemainingTimeFormatter] 的排除集一致，暂无法引用
     * `SponsorCategories.POI_HIGHLIGHT`（该文件不在本次改动范围内）。
     */
    private fun isPoi(segment: SponsorSegment): Boolean {
        return segment.actionType == "poi" || segment.category == POI_HIGHLIGHT
    }

    private fun clamp(value: Float, min: Float, max: Float): Float = minOf(maxOf(value, min), max)

    private const val POI_HIGHLIGHT = "poi_highlight"
}
