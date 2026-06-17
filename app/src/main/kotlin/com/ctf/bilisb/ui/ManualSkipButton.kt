package com.ctf.bilisb.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule

/**
 * 手动跳过按钮:开启"手动跳过"后,播放进入片段时在播放器右下角浮出一个
 * "跳过 xxx ▶" 按钮,由用户点按才跳过,而不是自动跳过。
 *
 * 类似 YouTube SponsorBlock 的跳过按钮。实现上把按钮直接挂到播放 Activity 的
 * decorView(FrameLayout)上,用 tag 去重复用;离开片段时隐藏。所有视图操作都
 * post 到主线程。
 */
object ManualSkipButton {
    private const val TAG = "com.ctf.bilisb.manual_skip_button"
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 显示/更新跳过按钮。
     *
     * @param host    播放器容器(用其 getContext 拿 Activity)
     * @param label   类别显示名,用于按钮文案
     * @param onSkip  点击回调(执行 seek)。点击后按钮自动隐藏。
     */
    fun show(module: XposedModule, host: Any, label: String, onSkip: () -> Unit) {
        handler.post {
            runCatching {
                val activity = playerActivity(host) ?: return@post
                val decor = activity.window?.decorView as? ViewGroup ?: return@post

                val button = (decor.findViewWithTag<View>(TAG) as? TextView)
                    ?: createButton(activity).also { decor.addView(it, buttonLayoutParams(activity)) }

                button.text = "跳过 $label ▶"
                button.visibility = View.VISIBLE
                button.setOnClickListener {
                    it.visibility = View.GONE
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
                val activity = playerActivity(host) ?: return@post
                val decor = activity.window?.decorView as? ViewGroup ?: return@post
                decor.findViewWithTag<View>(TAG)?.visibility = View.GONE
            }.onFailure {
                module.info("manual skip button hide failed: ${it.javaClass.name}: ${it.message}")
            }
        }
    }

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
            contentDescription = "手动跳过片段"
        }
    }

    private fun buttonLayoutParams(activity: Activity): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            // 抬高到进度条上方,避免遮挡控制栏
            bottomMargin = dp(activity, 72)
            rightMargin = dp(activity, 16)
        }
    }

    private fun playerActivity(host: Any): Activity? {
        val context = runCatching {
            host.javaClass.getDeclaredMethod("getContext").apply { isAccessible = true }.invoke(host)
        }.getOrNull()
        return context as? Activity
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
