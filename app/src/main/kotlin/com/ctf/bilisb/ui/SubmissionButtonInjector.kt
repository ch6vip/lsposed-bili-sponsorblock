package com.ctf.bilisb.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.ctf.bilisb.model.SponsorCategories
import com.ctf.bilisb.player.PlayerBridge
import com.ctf.bilisb.sponsor.SponsorBlockController
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 在播放页右侧操作栏注入 SponsorBlock 标记按钮。
 *
 * 6.5.0 的调用时机（`completeBind`）在 binder 线程上，而这里全是 View 操作，
 * 所以每个入口都先 post 到主线程；View 操作与 `AlertDialog.show()` 一律用
 * runCatching 兜住并留日志 —— 以前这里抛出的异常会直接顺着 Hook 回调
 * 打给宿主。
 */
object SubmissionButtonInjector {
    private const val BUTTON_TAG = "com.ctf.bilisb.sponsor_button"

    private val selectedCategoryByContext = ConcurrentHashMap<Int, String>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun attach(
        module: XposedModule,
        host: Any,
        controller: SponsorBlockController,
        contextHash: Int,
        defaultCategory: String = "sponsor",
    ) {
        // putIfAbsent：类别是用户选择，不能被下一次 attach 的默认值覆盖。
        selectedCategoryByContext.putIfAbsent(contextHash, sanitizeCategory(defaultCategory))

        mainHandler.post {
            runCatching {
                val activity = playerActivity(host) ?: run {
                    module.info("submission button skipped: player context is not Activity")
                    return@post
                }
                val actions = findActionsContainer(activity) ?: run {
                    module.info("submission button pending: actions_container_right not found")
                    attachWhenLayoutReady(module, activity, host, controller, contextHash, defaultCategory)
                    return@post
                }
                addButtonIfNeeded(module, host, actions, controller, contextHash, activity)
            }.onFailure {
                module.info("submission button attach failed: ${it.javaClass.name}: ${it.message}")
            }
        }
    }

    /**
     * 宿主布局还没就绪时的兜底：等 decorView 布局完成再挂。
     *
     * 必须在主线程调用（[attach] 已经 post 过）。
     */
    private fun attachWhenLayoutReady(
        module: XposedModule,
        activity: Activity,
        host: Any,
        controller: SponsorBlockController,
        contextHash: Int,
        defaultCategory: String,
    ) {
        val root = activity.window?.decorView ?: run {
            module.info("submission button pending: decorView is null")
            return
        }
        var layoutPasses = 0
        root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                runCatching {
                    val actions = findActionsContainer(activity)
                    if (actions == null) {
                        // 布局还没到：保留监听，下帧再来（原来是直接 return，其实返回的是
                        // 内层 lambda，监听被摘掉后再也不会重试）。
                        // 但也不能无限挂着：超过 60 帧还没出现就放弃，避免 decorView 长期持有监听。
                        if (++layoutPasses < 60) return@onLayoutChange
                        view.removeOnLayoutChangeListener(this)
                        module.info("submission button pending: actions_container_right never appeared")
                        return@onLayoutChange
                    }
                    selectedCategoryByContext.putIfAbsent(contextHash, sanitizeCategory(defaultCategory))
                    view.removeOnLayoutChangeListener(this)
                    addButtonIfNeeded(module, host, actions, controller, contextHash, activity)
                }.onFailure {
                    module.info("submission button late attach failed: ${it.javaClass.name}: ${it.message}")
                }
            }
        })
    }

    private fun addButtonIfNeeded(
        module: XposedModule,
        host: Any,
        actions: LinearLayout,
        controller: SponsorBlockController,
        contextHash: Int,
        activity: Activity,
    ) {
        if (actions.findViewWithTag<View>(BUTTON_TAG) != null) {
            return
        }

        val button = TextView(actions.context).apply {
            tag = BUTTON_TAG
            text = "SB"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            contentDescription = "SponsorBlock 标记"

            // 短按：标记当前位置
            setOnClickListener {
                runCatching {
                    val category = currentCategory(contextHash)
                    controller.markOrSubmitCurrentPosition(contextHash, category)
                    PlayerToastBridge.showMarkToast(module, host, "标记 ${SponsorCategories.displayName(category)}")
                }.onFailure {
                    module.info("submission button click failed: ${it.javaClass.name}: ${it.message}")
                }
            }

            // 长按：选择类别
            setOnLongClickListener {
                // 弹窗前再确认一次 Activity 还活着，否则会 WindowManager$BadTokenException。
                if (activity.isFinishing || activity.isDestroyed) {
                    module.info("category selector skipped: activity finishing/destroyed")
                    return@setOnLongClickListener true
                }
                showCategorySelector(activity, module, host, controller, contextHash)
                true
            }
        }

        val size = dp(actions, 40)
        val params = LinearLayout.LayoutParams(size, size).apply {
            rightMargin = dp(actions, 4)
        }
        actions.addView(button, 0, params)
        module.info("submission button attached to actions_container_right")
    }

    private fun showCategorySelector(
        activity: Activity,
        module: XposedModule,
        host: Any,
        controller: SponsorBlockController,
        contextHash: Int,
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            module.info("category selector skipped: activity finishing/destroyed")
            return
        }

        mainHandler.post {
            runCatching {
                val categories = SponsorCategories.displayNames.keys.toList()
                val categoryDisplayNames = categories.map { SponsorCategories.displayName(it) }.toTypedArray()
                val currentIndex = categories.indexOf(currentCategory(contextHash)).coerceAtLeast(0)

                AlertDialog.Builder(activity)
                    .setTitle("选择片段类别")
                    .setSingleChoiceItems(categoryDisplayNames, currentIndex) { dialog, which ->
                        runCatching {
                            val category = categories[which]
                            selectedCategoryByContext[contextHash] = category
                            PlayerToastBridge.showMarkToast(
                                module, host, "已选择: ${SponsorCategories.displayName(category)}",
                            )
                            dialog.dismiss()
                        }.onFailure {
                            module.info("category select failed: ${it.javaClass.name}: ${it.message}")
                        }
                    }
                    .setNegativeButton("取消标记") { dialog, _ ->
                        runCatching {
                            controller.cancelSubmissionDraft(contextHash)
                            PlayerToastBridge.showMarkToast(module, host, "已取消标记")
                            dialog.dismiss()
                        }.onFailure {
                            module.info("category cancel failed: ${it.javaClass.name}: ${it.message}")
                        }
                    }
                    .show()
            }.onFailure {
                module.info("category selector failed: ${it.javaClass.name}: ${it.message}")
            }
        }
    }

    /**
     * 播放器销毁时清理该上下文的类别记忆。
     *
     * 之前 `selectedCategoryByContext` 只写不清，播放器反复进出会一直留着旧 contextHash，
     * 且新播放器复用 hashCode 时会继承上一个视频的选择。
     */
    fun clearContext(contextHash: Int) {
        selectedCategoryByContext.remove(contextHash)
    }

    private fun findActionsContainer(activity: Activity): LinearLayout? {
        val id = activity.resources.getIdentifier("actions_container_right", "id", activity.packageName)
        val byId = if (id != 0) activity.findViewById<View>(id) else null
        return byId as? LinearLayout ?: findByResourceName(activity.window.decorView, "actions_container_right")
    }

    private fun findByResourceName(view: View?, targetName: String): LinearLayout? {
        if (view == null) {
            return null
        }
        if (view is LinearLayout && resourceEntryName(view) == targetName) {
            return view
        }
        val group = view as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val found = findByResourceName(group.getChildAt(i), targetName)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun resourceEntryName(view: View): String? {
        if (view.id == View.NO_ID) {
            return null
        }
        return runCatching {
            view.resources.getResourceEntryName(view.id)
        }.getOrNull()
    }

    private fun playerActivity(host: Any): Activity? {
        return PlayerBridge.activity(host)
    }

    private fun dp(view: View, value: Int): Int {
        return (value * view.resources.displayMetrics.density).toInt()
    }

    private fun sanitizeCategory(category: String): String =
        if (category in SponsorCategories.displayNames) category else "sponsor"

    private fun currentCategory(contextHash: Int): String =
        selectedCategoryByContext[contextHash]?.let(::sanitizeCategory) ?: "sponsor"
}
