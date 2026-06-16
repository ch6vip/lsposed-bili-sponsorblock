package com.ctf.bilisb.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import com.ctf.bilisb.model.SponsorSegment

object ProgressMarkerPainter {
    // 对应 APK `an.o`(分类颜色 Paint)。APK 按分类上色,这里先用统一黄色,
    // 分类颜色配置待设置页接入后对齐。
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 196, 0)
        style = Paint.Style.FILL
    }

    // 对应 APK `an.d()` = highlight/poi 分类,这类片段画圆点而非矩形。
    private val highlightCategory = "poi_highlight"

    fun draw(drawable: Drawable, canvas: Canvas, durationMs: Long, segments: List<SponsorSegment>) {
        if (durationMs <= 0 || segments.isEmpty()) {
            return
        }

        val bounds = drawable.bounds
        if (bounds.isEmpty) {
            return
        }

        val pxPerMs = bounds.width().toFloat() / durationMs.toFloat()
        val minWidth = bounds.height().coerceAtLeast(2) / 3f
        segments.forEach { segment ->
            // APK 画所有片段(skip 与 poi 都画),poi/highlight 用圆点。
            val isHighlight = segment.category == highlightCategory
            val left = bounds.left + segment.startMs.coerceAtLeast(0L) * pxPerMs
            val right = bounds.left + segment.endMs.coerceAtMost(durationMs) * pxPerMs
            if (isHighlight || right <= left) {
                canvas.drawCircle(left, bounds.centerY().toFloat(), bounds.height() / 2.0f, segmentPaint)
            } else {
                canvas.drawRect(left, bounds.top.toFloat(), right.coerceAtLeast(left + minWidth), bounds.bottom.toFloat(), segmentPaint)
            }
        }
    }
}
