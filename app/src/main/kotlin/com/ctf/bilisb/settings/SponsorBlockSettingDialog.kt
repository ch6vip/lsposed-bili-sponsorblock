package com.ctf.bilisb.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences

/**
 * 宿主内设置弹窗。
 *
 * 只负责弹窗导航和 [SettingsWriter] 生命周期；具体设置内容由 [SettingsScreenBuilder]
 * 构建，和模块入口 Activity 共用同一套 UI。
 */
object SponsorBlockSettingDialog {
    @Volatile
    private var writer: SettingsWriter? = null

    /** 当前正在显示的弹窗（主页面或详情页）：防止连点导致弹窗层层叠加。 */
    @Volatile
    private var currentDialog: AlertDialog? = null

    fun show(activity: Activity, onDismiss: (() -> Unit)? = null) {
        // Activity 已结束/正在结束时不能再 show()，否则会 WindowManager$BadTokenException
        if (activity.isFinishing || activity.isDestroyed) return
        // 已经有一个弹窗在显示时不叠加（宿主「我的」页入口可能被连点）
        if (currentDialog?.isShowing == true) return
        writer = SettingsWriter(activity)
        showMain(activity, onDismiss)
    }

    private fun prefs(activity: Activity): SharedPreferences =
        (writer ?: SettingsWriter(activity).also { writer = it }).sharedPreferences

    private fun showMain(activity: Activity, onDismiss: (() -> Unit)?) {
        if (activity.isFinishing || activity.isDestroyed) return
        val navigating = booleanArrayOf(false)
        val dialogRef = arrayOfNulls<AlertDialog>(1)
        val root = SettingsScreenBuilder.buildMain(activity) {
            navigating[0] = true
            dialogRef[0]?.dismiss()
            showDetail(activity, onDismiss)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Bili2233")
            .setView(SettingsScreenBuilder.wrapScroll(activity, root))
            .setPositiveButton("关闭", null)
            .create()
        dialog.setOnDismissListener {
            if (dialogRef[0] === currentDialog) currentDialog = null
            if (!navigating[0]) {
                // 根弹窗真正被关闭：释放 writer，避免静态引用长期持有旧 Activity 的 Context
                writer = null
                onDismiss?.invoke()
            }
        }
        dialogRef[0] = dialog
        currentDialog = dialog
        dialog.show()
    }

    private fun showDetail(activity: Activity, onDismiss: (() -> Unit)?) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialogRef = arrayOfNulls<AlertDialog>(1)
        fun back() {
            dialogRef[0]?.currentFocus?.clearFocus()
            // 先 dismiss 当前 detail 弹窗再导航:onClick 返回后 AOSP 会自动 dismiss,
            // 直接 showMain 会造成短暂双弹窗叠加(与 showMain 的导航写法保持一致)
            dialogRef[0]?.dismiss()
            showMain(activity, onDismiss)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("SponsorBlock")
            .setView(SettingsScreenBuilder.wrapScroll(activity, SettingsScreenBuilder.buildDetail(activity, prefs(activity))))
            .setPositiveButton("返回") { _, _ -> back() }
            .create()
        dialog.setOnCancelListener { back() }
        dialog.setOnDismissListener {
            // 只有当前登记的弹窗才清空，避免导航时把新弹窗的登记清掉
            if (dialogRef[0] === currentDialog) currentDialog = null
        }
        dialogRef[0] = dialog
        currentDialog = dialog
        dialog.show()
    }
}
