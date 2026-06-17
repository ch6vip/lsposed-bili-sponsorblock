package com.ctf.bilisb.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import com.ctf.bilisb.model.SponsorSegment

/**
 * 进度条片段标记绘制。
 *
 * 实现严格对齐官方 patch（Bili-v8.98.0 BiliRoamingX）的 `SponsorBlockPatch.b(zo, Drawable, Canvas)`：
 *   - 画在**轨道 drawable 自己的 bounds 上**（薄轨道高度 top→bottom），而不是父 View 的整高，
 *     这样标记和进度条等高、嵌在轨道里，而不是一根高 bar 浮在上面。
 *   - 用**实色**分类画笔（不带 alpha），对应 patch 里的 `anVar.o`。
 *   - 普通片段：`drawRect(xStart, bounds.top, xEnd, bounds.bottom)`。
 *   - POI 高亮：`drawCircle(xStart, centerY, height/2)`，对应 patch `anVar.d()` 分支。
 *
 * 调用点是 `seek.v3.f#draw(Canvas)`（薄轨道 drawable），与 patch 注入点一致。
 */
object ProgressMarkerPainter {
    // 分类配色兜底(实色,无 alpha)。当快照里没有该分类颜色时回退到这里。
    // 与 SettingsKeys.CATEGORY_COLOR_DEFAULTS 的默认值保持一致。
    private val defaultCategoryColors = mapOf(
        "sponsor" to Color.rgb(0, 210, 0),           // 绿色
        "selfpromo" to Color.rgb(255, 255, 0),       // 黄色
        "interaction" to Color.rgb(170, 0, 255),     // 紫色
        "intro" to Color.rgb(0, 255, 255),           // 青色
        "outro" to Color.rgb(0, 100, 255),           // 蓝色
        "preview" to Color.rgb(255, 128, 0),         // 橙色
        "music_offtopic" to Color.rgb(255, 0, 180),  // 粉色
        "filler" to Color.rgb(127, 0, 255),          // 深紫
        "poi_highlight" to Color.rgb(255, 30, 30),   // 红色
    )

    private val defaultColor = Color.rgb(255, 196, 0)

    // 复用单个 Paint，避免每帧 new。FILL + 抗锯齿（圆点更平滑）。
    private val paint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /**
     * 在轨道 drawable 上绘制标记。对应 patch 的 `b(...)`。
     *
     * @param drawable 轨道 drawable（seek.v3.f），用它的 bounds 决定标记的位置和高度
     * @param colorOverrides 用户在设置里自定义的分类颜色(category→ARGB)。缺该分类则回退内置配色。
     */
    fun draw(
        drawable: Drawable,
        canvas: Canvas,
        durationMs: Long,
        segments: List<SponsorSegment>,
        colorOverrides: Map<String, Int> = emptyMap(),
    ) {
        if (durationMs <= 0 || segments.isEmpty()) {
            return
        }

        val bounds = drawable.bounds
        if (bounds.isEmpty) {
            return
        }

        val width = bounds.width()
        val height = bounds.height()
        // px per ms（patch: f = iWidth / zoVar.d）
        val pxPerMs = width.toFloat() / durationMs.toFloat()

        val left = bounds.left
        val top = bounds.top.toFloat()
        val bottom = bounds.bottom.toFloat()
        val centerY = bounds.exactCenterY()
        val radius = height / 2.0f

        segments.forEach { segment ->
            paint.color = colorOverrides[segment.category]
                ?: defaultCategoryColors[segment.category]
                ?: defaultColor

            val xStart = left + segment.startMs * pxPerMs

            if (isPoi(segment)) {
                // POI 高亮：画圆点（对齐 patch anVar.d() 分支）
                canvas.drawCircle(xStart, centerY, radius, paint)
            } else {
                val xEnd = left + segment.endMs * pxPerMs
                canvas.drawRect(xStart, top, xEnd, bottom, paint)
            }
        }
    }

    private fun isPoi(segment: SponsorSegment): Boolean {
        return segment.actionType == "poi" || segment.category == "poi_highlight"
    }
}
