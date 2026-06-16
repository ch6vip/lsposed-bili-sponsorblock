package com.ctf.bilisb.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import com.ctf.bilisb.model.SponsorSegment

object ProgressMarkerPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 196, 0)
        style = Paint.Style.FILL
    }

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
            if (segment.actionType != "skip") {
                return@forEach
            }
            val left = bounds.left + segment.startMs.coerceAtLeast(0L) * pxPerMs
            val right = bounds.left + segment.endMs.coerceAtMost(durationMs) * pxPerMs
            if (right <= left) {
                canvas.drawCircle(left, bounds.centerY().toFloat(), bounds.height() / 2.0f, paint)
            } else {
                canvas.drawRect(left, bounds.top.toFloat(), (right).coerceAtLeast(left + minWidth), bounds.bottom.toFloat(), paint)
            }
        }
    }
}
