package com.ctf.bilisb.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * 「空降 / 定位」线性图标（外圈 + 圆心 + 四向刻度）。
 *
 * ## 为什么用 Canvas 画，而不是 res/ 资源或运行期矢量 XML
 *
 * 这个图标要在**宿主进程**里构造行视图时现画，模块里没法直接拿自己的 `R.drawable`
 * （宿主 Resources 里没有我们的资源）。常见做法是拼一段矢量 XML 字符串喂给
 * `VectorDrawable.createFromXml(Resources, XmlPullParser)`，但那条路有个不确定点：
 * 需要 `FEATURE_PROCESS_NAMESPACES=false`，此时 `android:` 前缀属性的命名空间会丢失，
 * `Resources.obtainAttributes` 能否命中 framework 属性随 ROM/框架版本而异 —— 命中失败时
 * **不会抛异常**，只会得到一个没有 pathData 的空图标，表现为「图标位置空了」。
 * 直接画到 Canvas 上则完全没有这条解析路径。
 *
 * ## 尺寸
 *
 * 全部按 [bounds] 的短边等比算，默认用在 20dp 的 ImageView 上。
 * 实测宿主行图标（20dp 框）的墨迹约 19dp 宽、描边约 1.5dp，这里按同一比例对齐。
 *
 * @param color 描边/填充色，调用方传入标题色，保证图标与标题同色（宿主就是这么做的）。
 */
class TargetIconDrawable(color: Int) : Drawable() {

    /** 墨迹（含描边）半宽占短边的比例。 */
    private val halfInkRatio = 0.4375f

    /** 描边宽度占短边的比例（20dp 框 → 1.5dp 描边）。 */
    private val strokeRatio = 0.075f

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        setColor(color)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setColor(color)
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return

        val shortSide = min(bounds.width(), bounds.height()).toFloat()
        val strokeWidth = shortSide * strokeRatio
        strokePaint.strokeWidth = strokeWidth

        // 刻度线画到 tickOuterR，加上 ROUND 端帽正好落在墨迹边界 halfInk
        val tickOuterR = shortSide * halfInkRatio - strokeWidth / 2f
        if (tickOuterR <= strokeWidth) return

        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()

        // 外圈（描边圆心半径）与圆心点
        canvas.drawCircle(cx, cy, tickOuterR * 0.52f, strokePaint)
        canvas.drawCircle(cx, cy, tickOuterR * 0.13f, fillPaint)

        // 四向刻度：上下左右各一段
        val innerR = tickOuterR * 0.82f
        canvas.drawLine(cx, cy - tickOuterR, cx, cy - innerR, strokePaint)
        canvas.drawLine(cx, cy + innerR, cx, cy + tickOuterR, strokePaint)
        canvas.drawLine(cx - tickOuterR, cy, cx - innerR, cy, strokePaint)
        canvas.drawLine(cx + innerR, cy, cx + tickOuterR, cy, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        strokePaint.alpha = alpha
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
