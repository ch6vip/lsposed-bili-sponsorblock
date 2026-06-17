package com.ctf.bilisb.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.ctf.bilisb.sponsor.SponsorBlockController
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule

object SubmissionButtonInjector {
    private const val BUTTON_TAG = "com.ctf.bilisb.sponsor_button"

    // 当前选中的类别
    private var currentCategory = "sponsor"

    // 类别名称映射
    private val categoryNames = mapOf(
        "sponsor" to "赞助/恰饭",
        "selfpromo" to "自我推广",
        "interaction" to "互动提醒",
        "intro" to "开场动画",
        "outro" to "结束画面",
        "preview" to "回顾/概要",
        "music_offtopic" to "非音乐片段",
        "filler" to "填充内容",
        "poi_highlight" to "精彩时刻"
    )

    fun attach(
        module: XposedModule,
        host: Any,
        controller: SponsorBlockController,
        contextHash: Int,
    ) {
        val activity = playerActivity(host) ?: run {
            module.info("submission button skipped: player context is not Activity")
            return
        }
        val actions = findActionsContainer(activity) ?: run {
            module.info("submission button pending: actions_container_right not found")
            attachWhenLayoutReady(module, activity, host, controller, contextHash)
            return
        }
        addButtonIfNeeded(module, host, actions, controller, contextHash, activity)
    }

    private fun attachWhenLayoutReady(
        module: XposedModule,
        activity: Activity,
        host: Any,
        controller: SponsorBlockController,
        contextHash: Int,
    ) {
        val root = activity.window?.decorView ?: return
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
                val actions = findActionsContainer(activity) ?: return
                view.removeOnLayoutChangeListener(this)
                addButtonIfNeeded(module, host, actions, controller, contextHash, activity)
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
                controller.markOrSubmitCurrentPosition(contextHash, currentCategory)
                PlayerToastBridge.showMarkToast(module, host, "标记 ${categoryNames[currentCategory]}")
            }

            // 长按：选择类别
            setOnLongClickListener {
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
        val categories = categoryNames.keys.toList()
        val categoryDisplayNames = categories.map { categoryNames[it] ?: it }.toTypedArray()
        val currentIndex = categories.indexOf(currentCategory).coerceAtLeast(0)

        AlertDialog.Builder(activity)
            .setTitle("选择片段类别")
            .setSingleChoiceItems(categoryDisplayNames, currentIndex) { dialog, which ->
                currentCategory = categories[which]
                PlayerToastBridge.showMarkToast(module, host, "已选择: ${categoryNames[currentCategory]}")
                dialog.dismiss()
            }
            .setNegativeButton("取消标记") { dialog, _ ->
                controller.cancelSubmissionDraft(contextHash)
                PlayerToastBridge.showMarkToast(module, host, "已取消标记")
                dialog.dismiss()
            }
            .show()
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
        val context = runCatching {
            host.javaClass.getDeclaredMethod("getContext").apply { isAccessible = true }.invoke(host)
        }.getOrNull()
        return context as? Activity
    }

    private fun dp(view: View, value: Int): Int {
        return (value * view.resources.displayMetrics.density).toInt()
    }
}
