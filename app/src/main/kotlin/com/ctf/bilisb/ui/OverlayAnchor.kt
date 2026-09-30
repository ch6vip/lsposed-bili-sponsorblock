package com.ctf.bilisb.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 播放器内浮层（手动跳过按钮 / 自动跳过倒计时）的**挂载点解析**。
 *
 * ## 为什么不再直接挂 decorView
 *
 * 以前两个浮层都挂到 `Activity.window.decorView`，再用 `gravity = BOTTOM|END` +
 * `bottomMargin = 72dp` 定位。decorView 是**整屏**坐标，所以：
 *   - 详情页向下滚动、播放器被顶出屏幕时，浮层仍贴在屏幕右下角（离播放器很远）；
 *   - 小窗 / PiP 里播放器只占屏幕一角，浮层却按整屏右下角算位置。
 *
 * 现在优先挂到**播放器自己的容器**（`bindPlayerContainer` 拿到的 widget 的父 ViewGroup），
 * 浮层因此天然跟随播放器 bounds。但「优先」不能变成「无条件」：父容器可能还没 attach、
 * 还没测量、靠 `clipChildren` 裁子 View，甚至本身就覆盖全屏（挂上去等于没跟随）。
 * 所以判定分两层：
 *   - [isUsableAnchor]：纯函数（无 android 依赖）判定「尺寸与裁剪是否允许承载浮层」，有单测；
 *   - [resolve]：真实 View 树上的取用与回落，回落原因交给调用方打探针 ——
 *     浮层类问题在真机上只表现为「按钮/浮层不出现」，没有记录就只能靠猜。
 */
object OverlayAnchor {

    /** 播放器容器至少要占屏幕这么大，才认为它是「真的播放器」而不是装饰性小 View。 */
    const val MIN_PLAYER_FRACTION = 0.25f

    /** 挂载点接近整屏（≥ 这个比例）时认为「跟随播放器」没有意义，回落 decorView。 */
    const val MAX_ANCHOR_FRACTION = 0.90f

    /** 浮层与底部控制栏（进度条）之间的边距(dp)。与旧实现保持一致。 */
    private const val BOTTOM_MARGIN_DP = 72
    private const val SIDE_MARGIN_DP = 16

    /**
     * 解析结果。
     *
     * @param parent 最终挂载的容器
     * @param reason 判定说明（探针文案）：`playerContainer` 表示真的挂到了播放器容器上
     */
    data class Resolved(val parent: ViewGroup, val reason: String) {
        /** 是否真的跟随了播放器（而不是回落到整屏 decorView）。 */
        val followsPlayer: Boolean get() = reason.startsWith(PREFIX_PLAYER)
    }

    private const val PREFIX_PLAYER = "playerContainer"

    /**
     * 纯判定：给定播放器与挂载容器的尺寸/裁剪/attach 状态，是否允许把浮层挂到 [anchor] 上。
     *
     * @return null 表示可用；否则返回**不可用的原因**（英文短语，直接进探针/测试断言）
     */
    fun isUsableAnchor(
        playerWidth: Int,
        playerHeight: Int,
        decorWidth: Int,
        decorHeight: Int,
        anchorWidth: Int,
        anchorHeight: Int,
        anchorClipsChildren: Boolean,
        playerAttached: Boolean,
        anchorAttached: Boolean,
    ): String? {
        if (decorWidth <= 0 || decorHeight <= 0) return "decorNotMeasured"
        if (!playerAttached) return "playerNotAttached"
        if (playerWidth <= 0 || playerHeight <= 0) return "playerNotMeasured"
        if (playerWidth < decorWidth * MIN_PLAYER_FRACTION ||
            playerHeight < decorHeight * MIN_PLAYER_FRACTION
        ) {
            return "playerTooSmall(${playerWidth}x$playerHeight vs ${decorWidth}x$decorHeight)"
        }
        if (!anchorAttached) return "anchorNotAttached"
        if (anchorWidth <= 0 || anchorHeight <= 0) return "anchorNotMeasured"
        if (anchorWidth >= decorWidth * MAX_ANCHOR_FRACTION &&
            anchorHeight >= decorHeight * MAX_ANCHOR_FRACTION
        ) {
            return "anchorCoversScreen(${anchorWidth}x$anchorHeight)"
        }
        // 宿主用 clipChildren 裁子 View 时，浮层会被裁没 —— 不如退回 decorView（至少可见）。
        if (anchorClipsChildren) return "anchorClipsChildren"
        return null
    }

    /**
     * 在真实 View 树上解析挂载点。
     *
     * @param host `bindPlayerContainer` 拿到的播放器容器 widget（一般是个 View）
     * @return 解析结果；连 decorView 都取不到时返回 null（调用方按失败处理并打探针）
     */
    fun resolve(activity: Activity, host: Any): Resolved? {
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        val player = host as? View ?: return Resolved(decor, "hostNotAView(${host.javaClass.name})")

        // 播放器本身就是整屏容器时没有「跟随」可言，直接用 decorView。
        if (player === decor) return Resolved(decor, "playerIsDecor")

        val anchor = player.parent as? ViewGroup
            ?: return Resolved(decor, "playerHasNoParent")

        val reason = isUsableAnchor(
            playerWidth = player.width,
            playerHeight = player.height,
            decorWidth = decor.width,
            decorHeight = decor.height,
            anchorWidth = anchor.width,
            anchorHeight = anchor.height,
            anchorClipsChildren = anchor.clipChildren,
            playerAttached = player.isAttachedToWindow,
            anchorAttached = anchor.isAttachedToWindow,
        )
        return if (reason == null) {
            Resolved(anchor, "$PREFIX_PLAYER(${anchor.javaClass.name})")
        } else {
            Resolved(decor, reason)
        }
    }

    /** 把 [view] 挂到 [parent] 的右下角，返回实际使用的布局参数。 */
    fun addBottomEnd(parent: ViewGroup, view: View): ViewGroup.LayoutParams {
        val lp = layoutParams(parent, view)
        parent.addView(view, lp)
        return lp
    }

    /** 右下角定位的布局参数；[parent] 是 FrameLayout 时带 margin，否则退化为普通 WRAP_CONTENT。 */
    fun layoutParams(parent: ViewGroup, view: View): ViewGroup.LayoutParams {
        val base = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        if (parent !is FrameLayout) return base
        val density = view.resources.displayMetrics.density
        return FrameLayout.LayoutParams(base.width, base.height).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(density, BOTTOM_MARGIN_DP)
            rightMargin = dp(density, SIDE_MARGIN_DP)
        }
    }

    private fun dp(density: Float, value: Int): Int = (value * density).toInt()
}
