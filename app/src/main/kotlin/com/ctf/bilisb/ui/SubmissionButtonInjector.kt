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
import com.ctf.bilisb.model.SponsorCategories
import com.ctf.bilisb.sponsor.SponsorBlockController
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

object SubmissionButtonInjector {
    private const val BUTTON_TAG = "com.ctf.bilisb.sponsor_button"

    private val selectedCategoryByContext = ConcurrentHashMap<Int, String>()

    fun attach(
        module: XposedModule,
        host: Any,
        controller: SponsorBlockController,
        contextHash: Int,
        defaultCategory: String = "sponsor",
    ) {
        selectedCategoryByContext[contextHash] = sanitizeCategory(defaultCategory)
        val activity = playerActivity(host) ?: run {
            module.info("submission button skipped: player context is not Activity")
            return
        }
        val actions = findActionsContainer(activity) ?: run {
            module.info("submission button pending: actions_container_right not found")
            attachWhenLayoutReady(module, activity, host, controller, contextHash, defaultCategory)
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
        defaultCategory: String,
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
                selectedCategoryByContext[contextHash] = sanitizeCategory(defaultCategory)
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
                val category = currentCategory(contextHash)
                controller.markOrSubmitCurrentPosition(contextHash, category)
                PlayerToastBridge.showMarkToast(module, host, "标记 ${SponsorCategories.displayName(category)}")
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
        val categories = SponsorCategories.displayNames.keys.toList()
        val categoryDisplayNames = categories.map { SponsorCategories.displayName(it) }.toTypedArray()
        val currentIndex = categories.indexOf(currentCategory(contextHash)).coerceAtLeast(0)

        AlertDialog.Builder(activity)
            .setTitle("选择片段类别")
            .setSingleChoiceItems(categoryDisplayNames, currentIndex) { dialog, which ->
                val category = categories[which]
                selectedCategoryByContext[contextHash] = category
                PlayerToastBridge.showMarkToast(module, host, "已选择: ${SponsorCategories.displayName(category)}")
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

    private fun sanitizeCategory(category: String): String =
        if (category in SponsorCategories.displayNames) category else "sponsor"

    private fun currentCategory(contextHash: Int): String =
        selectedCategoryByContext[contextHash]?.let(::sanitizeCategory) ?: "sponsor"
}
