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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule
import kotlin.math.ceil
import java.util.WeakHashMap

/**
 * 自动跳过倒计时浮层:进入片段后显示"N秒后跳过 xxx [取消]",每秒递减,
 * 倒计时结束触发 [onComplete](执行 seek)。用户点"取消"则中止,不跳过。
 *
 * 浮层挂在播放 Activity 的 decorView(FrameLayout)上,用 tag 去重复用;
 * 每秒一个 Handler tick。同一时间只维护一个倒计时,[start] 会替换上一个。
 */
object SkipCountdownOverlay {
    private const val TAG = "com.ctf.bilisb.skip_countdown"
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private data class CountdownState(
        var ticker: Runnable? = null,
    )

    // 只在 main handler 中访问；弱引用避免 Activity 销毁后被静态单例持有。
    private val stateByActivity = WeakHashMap<Activity, CountdownState>()

    /**
     * @param totalMs    倒计时总时长(ms)
     * @param onComplete 倒计时自然结束(未取消)时回调,执行跳过
     * @param onCancel   用户点取消时回调
     */
    fun start(
        module: XposedModule,
        host: Any,
        label: String,
        totalMs: Long,
        onComplete: () -> Unit,
        onCancel: () -> Unit,
    ) {
        handler.post {
            runCatching {
                val activity = playerActivity(host) ?: return@post
                val decor = activity.window?.decorView as? ViewGroup ?: return@post

                val state = stateFor(activity)
                cancelTicker(state)

                val row = (decor.findViewWithTag<View>(TAG) as? LinearLayout)
                    ?: buildRow(activity).also { decor.addView(it, layoutParams(activity)) }
                row.visibility = View.VISIBLE

                val text = row.getChildAt(0) as TextView
                val cancelBtn = row.getChildAt(1) as Button

                var remaining = ceil(totalMs / 1000.0).toInt().coerceAtLeast(1)

                cancelBtn.setOnClickListener {
                    cancelTicker(state)
                    row.visibility = View.GONE
                    module.info("auto-skip countdown canceled by user")
                    onCancel()
                }

                val tick = object : Runnable {
                    override fun run() {
                        if (remaining <= 0) {
                            row.visibility = View.GONE
                            state.ticker = null
                            onComplete()
                            return
                        }
                        text.text = "${remaining}秒后跳过 $label"
                        remaining--
                        state.ticker = this
                        handler.postDelayed(this, 1000)
                    }
                }
                tick.run()
            }.onFailure {
                module.info("skip countdown start failed: ${it.javaClass.name}: ${it.message}")
            }
        }
    }

    /** 外部取消(离开片段/切片段时)。不触发任何回调。 */
    fun cancel(module: XposedModule, host: Any) {
        handler.post {
            runCatching {
                val activity = playerActivity(host) ?: return@post
                val state = stateByActivity[activity] ?: return@post
                cancelTicker(state)
                val decor = activity.window?.decorView as? ViewGroup ?: return@post
                decor.findViewWithTag<View>(TAG)?.visibility = View.GONE
            }
        }
    }

    private fun stateFor(activity: Activity): CountdownState {
        return stateByActivity.getOrPut(activity) { CountdownState() }
    }

    private fun cancelTicker(state: CountdownState) {
        state.ticker?.let { handler.removeCallbacks(it) }
        state.ticker = null
    }

    private fun buildRow(activity: Activity): LinearLayout {
        return LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 8), dp(activity, 8))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 20).toFloat()
                setColor(0xCC000000.toInt())
                setStroke(dp(activity, 1), 0xFFFB7299.toInt())
            }
            addView(TextView(activity).apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
            })
            addView(Button(activity).apply {
                text = "取消"
                textSize = 13f
                isAllCaps = false
                setTextColor(0xFFFB7299.toInt())
                setBackgroundColor(Color.TRANSPARENT)
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(activity, 8), 0, dp(activity, 8), 0)
            })
        }
    }

    private fun layoutParams(activity: Activity): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
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
