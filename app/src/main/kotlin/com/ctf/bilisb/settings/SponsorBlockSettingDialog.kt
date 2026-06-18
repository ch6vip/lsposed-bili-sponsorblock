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

    fun show(activity: Activity, onDismiss: (() -> Unit)? = null) {
        writer = SettingsWriter(activity)
        showMain(activity, onDismiss)
    }

    private fun prefs(activity: Activity): SharedPreferences =
        (writer ?: SettingsWriter(activity).also { writer = it }).sharedPreferences

    private fun showMain(activity: Activity, onDismiss: (() -> Unit)?) {
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
        dialog.setOnDismissListener { if (!navigating[0]) onDismiss?.invoke() }
        dialogRef[0] = dialog
        dialog.show()
    }

    private fun showDetail(activity: Activity, onDismiss: (() -> Unit)?) {
        val dialogRef = arrayOfNulls<AlertDialog>(1)
        fun back() {
            dialogRef[0]?.currentFocus?.clearFocus()
            showMain(activity, onDismiss)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("SponsorBlock")
            .setView(SettingsScreenBuilder.wrapScroll(activity, SettingsScreenBuilder.buildDetail(activity, prefs(activity))))
            .setPositiveButton("返回") { _, _ -> back() }
            .create()
        dialog.setOnCancelListener { back() }
        dialogRef[0] = dialog
        dialog.show()
    }
}
