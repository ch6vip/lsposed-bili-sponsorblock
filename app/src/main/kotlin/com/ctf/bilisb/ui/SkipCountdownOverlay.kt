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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.player.PlayerBridge
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule
import kotlin.math.ceil
import java.util.WeakHashMap

/**
 * 自动跳过倒计时浮层:进入片段后显示"N秒后跳过 xxx [取消]",每秒递减,
 * 倒计时结束触发 [onComplete](执行 seek)。用户点"取消"则中止,不跳过。
 *
 * 浮层挂在播放 Activity 的 decorView(FrameLayout)上,用 tag 去重复用。
 * 倒计时是 **deadline 驱动**的：进入时记下 `deadline = uptimeMillis + totalMs`，
 * 每次 tick 用真实时间重算剩余秒数。旧的「每秒减一」实现会因为主线程卡顿
 * （宿主播放页首帧、GC）累积漂移，用户看到的 5 秒可能拖到 8 秒。
 */
object SkipCountdownOverlay {
    private const val TAG = "com.ctf.bilisb.skip_countdown"

    /** 倒计时结束后同样要抑制：seek 生效前的进度回调会让浮层重开。 */
    private const val COMPLETE_SUPPRESS_MS = 2_000L

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private data class CountdownState(
        var ticker: Runnable? = null,
    )

    // 只在 main handler 中访问；弱引用避免 Activity 销毁后被静态单例持有。
    private val stateByActivity = WeakHashMap<Activity, CountdownState>()

    private val suppression = SkipSuppression(COMPLETE_SUPPRESS_MS)

    /**
     * @param totalMs    倒计时总时长(ms)
     * @param segmentKey 片段标识。完成后进入抑制窗口,重复请求会被忽略;
     *                   传空串时不做抑制(避免不同片段互相压制)。
     * @param onComplete 倒计时自然结束(未取消)时回调,执行跳过
     * @param onCancel   用户点取消时回调
     */
    fun start(
        module: XposedModule,
        host: Any,
        label: String,
        totalMs: Long,
        segmentKey: String = "",
        onComplete: () -> Unit,
        onCancel: () -> Unit,
    ) {
        handler.post {
            runCatching {
                val activity = playerActivity(host) ?: run {
                    probeNoActivity(module, "countdownNoActivity", host)
                    return@post
                }
                val decor = activity.window?.decorView as? ViewGroup ?: run {
                    probeNoActivity(module, "countdownNoDecor", host)
                    return@post
                }
                if (activity.isFinishing || activity.isDestroyed) {
                    HookProbe.first(module, "countdownActivityGone", 3) {
                        "countdown start: activity finishing/destroyed, host=${host.javaClass.name}"
                    }
                    return@post
                }

                val scope = System.identityHashCode(activity)
                if (segmentKey.isNotEmpty() &&
                    suppression.isSuppressed(scope, segmentKey, System.currentTimeMillis())
                ) {
                    return@post
                }

                val state = stateFor(activity)
                cancelTicker(state)

                val row = (decor.findViewWithTag<View>(TAG) as? LinearLayout)
                    ?: buildRow(activity).also { decor.addView(it, layoutParams(activity)) }
                row.visibility = View.VISIBLE

                val text = row.getChildAt(0) as TextView
                val cancelBtn = row.getChildAt(1) as Button

                // deadline 驱动：文本永远由 (deadline - now) 反算，不依赖 tick 次数。
                val deadline = SystemClock.uptimeMillis() + totalMs.coerceAtLeast(0L)

                cancelBtn.setOnClickListener {
                    cancelTicker(state)
                    row.visibility = View.GONE
                    module.info("auto-skip countdown canceled by user")
                    onCancel()
                }

                val tick = object : Runnable {
                    override fun run() {
                        // 每 tick 先自检生命周期：Activity 销毁 / 浮层被摘掉 / 不可见时
                        // 必须立刻停，否则会对已销毁的宿主 seek、记统计、弹 Toast。
                        if (!isAlive(activity, row)) {
                            cancelTicker(state)
                            module.info(
                                "auto-skip countdown stopped: host detached " +
                                    "(attached=${row.isAttachedToWindow} shown=${row.isShown})",
                            )
                            return
                        }

                        val now = SystemClock.uptimeMillis()
                        if (now >= deadline) {
                            row.visibility = View.GONE
                            state.ticker = null
                            // 只有拿到片段标识才记账，否则不同片段会互相压制。
                            if (segmentKey.isNotEmpty()) {
                                suppression.suppress(scope, segmentKey, System.currentTimeMillis())
                            }
                            onComplete()
                            return
                        }

                        val remaining = ceil((deadline - now) / 1000.0).toInt().coerceAtLeast(1)
                        text.text = "${remaining}秒后跳过 $label"
                        state.ticker = this
                        // 绝对时间调度：不需要累计漂移补偿，也不会因延迟而少跳一拍。
                        handler.postAtTime(this, now + 1_000L)
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
                val activity = playerActivity(host) ?: run {
                    probeNoActivity(module, "countdownCancelNoActivity", host)
                    return@post
                }
                val state = stateByActivity[activity] ?: return@post
                cancelTicker(state)
                val decor = activity.window?.decorView as? ViewGroup ?: return@post
                decor.findViewWithTag<View>(TAG)?.visibility = View.GONE
            }
        }
    }

    /** 浮层是否还应该继续倒计时。任何一条不成立都立即停。 */
    private fun isAlive(activity: Activity, row: View): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        if (!row.isAttachedToWindow) return false
        if (!row.isShown) return false
        return true
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
        // 6.5.0：容器取 Context 的方法名是 t()，且拿到的是 ContextWrapper，需要解包才是 Activity
        return PlayerBridge.activity(host)
    }

    /**
     * 「取不到 Activity / 挂载点」以前是静默 return，真机上只表现为「倒计时不出现」。
     * 这里限频打出宿主实际类型 + Context 类型。
     */
    private fun probeNoActivity(module: XposedModule, key: String, host: Any) {
        HookProbe.first(module, key, 3) {
            "host=${host.javaClass.name} context=${PlayerBridge.context(host)?.javaClass?.name}"
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
