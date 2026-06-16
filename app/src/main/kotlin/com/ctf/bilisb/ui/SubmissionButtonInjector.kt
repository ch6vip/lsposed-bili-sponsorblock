package com.ctf.bilisb.ui

import android.app.Activity
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
        addButtonIfNeeded(module, host, actions, controller, contextHash)
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
                addButtonIfNeeded(module, host, actions, controller, contextHash)
            }
        })
    }

    private fun addButtonIfNeeded(
        module: XposedModule,
        host: Any,
        actions: LinearLayout,
        controller: SponsorBlockController,
        contextHash: Int,
    ) {
        if (actions.findViewWithTag<View>(BUTTON_TAG) != null) {
            return
        }

        // APK injects a ControlWidgetLinearLayout with two ImageViews into
        // actions_container_right. This LSPosed module cannot rely on the
        // ReVanced-only widget/resources, so this is a lightweight inferred
        // equivalent: one stable control that toggles mark-start/mark-end and
        // long-press cancels the current draft.
        val button = TextView(actions.context).apply {
            tag = BUTTON_TAG
            text = "SB"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            contentDescription = "SponsorBlock 标记"
            setOnClickListener {
                controller.markOrSubmitCurrentPosition(contextHash)
                PlayerToastBridge.showSkipToast(module, host, "SponsorBlock 标记")
            }
            setOnLongClickListener {
                controller.cancelSubmissionDraft(contextHash)
                PlayerToastBridge.showSkipToast(module, host, "SponsorBlock 已取消标记")
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
