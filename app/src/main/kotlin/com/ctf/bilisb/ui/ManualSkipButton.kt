package com.ctf.bilisb.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.player.PlayerBridge
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule

/**
 * 手动跳过按钮:开启"手动跳过"后,播放进入片段时在播放器右下角浮出一个
 * "跳过 xxx ▶" 按钮,由用户点按才跳过,而不是自动跳过。
 *
 * 类似 YouTube SponsorBlock 的跳过按钮。挂载点由 [OverlayAnchor] 解析：
 * 优先挂在**播放器容器**上（跟随播放器 bounds，详情页滚动/小窗时位置才正确），
 * 容器不可用时回落整屏 decorView（老行为），回落原因进探针。
 * 用 tag 去重复用；离开片段时隐藏。所有视图操作都 post 到主线程。
 */
object ManualSkipButton {
    private const val TAG = "com.ctf.bilisb.manual_skip_button"

    /** 点击跳过后的抑制窗口：seek 生效前进度回调仍在片段内，按钮会闪回。 */
    private const val SKIP_SUPPRESS_MS = 2_000L

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // key 由 contextHash + 片段标识组成，不同播放器/视频互不影响。
    private val suppression = SkipSuppression(SKIP_SUPPRESS_MS)

    /**
     * 显示/更新跳过按钮。
     *
     * @param host       播放器容器(用其 getContext 拿 Activity)
     * @param label      类别显示名,用于按钮文案
     * @param segmentKey 片段标识(例如 `"$videoKey:$uuid:$startMs-$endMs"`)。跳过后的抑制按它记账:
     *                   传空串时**不做抑制**(避免不同片段互相压制),拨动点需要显式传入。
     * @param onSkip     点击回调(执行 seek)。点击后按钮自动隐藏,并进入抑制窗口。
     */
    fun show(
        module: XposedModule,
        host: Any,
        label: String,
        segmentKey: String = "",
        onSkip: () -> Unit,
    ) {
        handler.post {
            runCatching {
                val activity = playerActivity(host) ?: run {
                    probeNoActivity(module, "manualSkipNoActivity", host)
                    return@post
                }
                // Activity 已在销毁路上时不要再挂 View（会泄漏 decorView）。
                if (activity.isFinishing || activity.isDestroyed) {
                    HookProbe.first(module, "manualSkipActivityGone", 3) {
                        "skip show: activity finishing/destroyed, host=${host.javaClass.name}"
                    }
                    return@post
                }
                // 挂载点：优先播放器容器（跟随播放器 bounds），不可用时回落 decorView（整屏右下角）。
                // 回落原因进探针 —— 否则真机上「按钮位置不对/不出现」无法区分是宿主改版还是回落。
                val resolved = OverlayAnchor.resolve(activity, host) ?: run {
                    probeNoActivity(module, "manualSkipNoAnchor", host)
                    return@post
                }
                if (!resolved.followsPlayer) {
                    HookProbe.first(module, "manualSkipAnchorFallback", 3) {
                        "回落 decorView: ${resolved.reason} host=${host.javaClass.name}"
                    }
                }

                val scope = System.identityHashCode(activity)
                if (segmentKey.isNotEmpty() &&
                    suppression.isSuppressed(scope, segmentKey, SystemClock.uptimeMillis())
                ) {
                    return@post
                }

                val root = resolved.parent
                val existing = (activity.window?.decorView as? ViewGroup)?.findViewWithTag<View>(TAG)
                val button = (existing as? TextView) ?: createButton(activity).also {
                    OverlayAnchor.addBottomEnd(root, it)
                }
                // 复用的按钮可能挂在上一轮的另一个容器上（整屏切换/回落变化），确保它在当前挂载点里。
                if (button.parent !== root) {
                    (button.parent as? ViewGroup)?.removeView(button)
                    OverlayAnchor.addBottomEnd(root, button)
                }

                button.text = button.resources.getString(
                    com.ctf.bilisb.R.string.manual_skip_button,
                    label,
                )
                button.visibility = View.VISIBLE
                button.setOnClickListener {
                    it.visibility = View.GONE
                    // 先记账再 seek：seek 是异步的，抑制窗口要覆盖它生效前的几帧回调。
                    if (segmentKey.isNotEmpty()) {
                        suppression.suppress(scope, segmentKey, SystemClock.uptimeMillis())
                    }
                    onSkip()
                }
            }.onFailure {
                module.info("manual skip button show failed: ${it.javaClass.name}: ${it.message}")
            }
        }
    }

    /** 隐藏跳过按钮(离开片段或关闭手动模式时)。 */
    fun hide(module: XposedModule, host: Any) {
        handler.post {
            runCatching {
                val activity = playerActivity(host) ?: run {
                    probeNoActivity(module, "manualSkipHideNoActivity", host)
                    return@post
                }
                // 用带 tag 的 View 自己所在的父容器来隐藏：按钮可能挂在播放器容器上，
                // 也可能回落在 decorView 上，从 root 递归找都能命中。
                findButton(activity)?.visibility = View.GONE
            }.onFailure {
                module.info("manual skip button hide failed: ${it.javaClass.name}: ${it.message}")
            }
        }
    }

    /** 在整棵窗口视图树里找我们的按钮（挂载点会随回落/整屏切换变化）。 */
    private fun findButton(activity: Activity): View? =
        (activity.window?.decorView as? ViewGroup)?.findViewWithTag(TAG)

    private fun createButton(activity: Activity): TextView {
        return TextView(activity).apply {
            tag = TAG
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8))
            // 半透明黑底 + 圆角胶囊
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 20).toFloat()
                setColor(0xCC000000.toInt())
                setStroke(dp(activity, 1), 0xFFFB7299.toInt()) // B站粉描边
            }
            contentDescription = resources.getString(com.ctf.bilisb.R.string.manual_skip_button_desc)
        }
    }

    private fun playerActivity(host: Any): Activity? {
        // 6.5.0：容器取 Context 的方法名是 t()，且拿到的是 ContextWrapper，需要解包才是 Activity
        return PlayerBridge.activity(host)
    }

    /**
     * 「取不到 Activity / 挂载点」以前是静默 return，真机上只表现为「按钮不出现」。
     * 这里限频打出宿主实际类型 + Context 类型，便于区分是宿主改版还是包装层问题。
     */
    private fun probeNoActivity(module: XposedModule, key: String, host: Any) {
        HookProbe.first(module, key, 3) {
            "host=${host.javaClass.name} context=${PlayerBridge.context(host)?.javaClass?.name}"
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
