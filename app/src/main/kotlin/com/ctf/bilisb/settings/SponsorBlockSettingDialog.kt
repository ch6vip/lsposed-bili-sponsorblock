package com.ctf.bilisb.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences

/**
 * 宿主内设置弹窗。
 *
 * 弹窗层级(每层都有专名,文档/日志/沟通统一用这套叫法):
 *   - **控制中心**(`showMain`,标题「控制中心」):总入口,聚合 SponsorBlock、
 *     B 站增强两个入口行与「关于」;
 *   - **SponsorBlock 详情**(`showDetail`,标题「SponsorBlock」):跳过/静音/统计/服务器等;
 *   - **B 站增强详情**(`showEnhance`,标题「B 站增强」):移植自 BiliTamer 的增强开关。
 *
 * 本类只负责弹窗导航和 [SettingsWriter] 生命周期；具体设置内容由
 * [SettingsScreenBuilder] 构建，和模块入口 Activity 共用同一套 UI。
 */
object SponsorBlockSettingDialog {

    /**
     * 弹窗 R 角:窗口背景置透明,内容根容器(biliPage/buildMain 的 12dp 圆角)即弹窗轮廓。
     * AlertDialog 默认窗口底是主题的方角白底,会把圆角内容衬成方角。
     */
    private fun applyRoundedWindow(dialog: AlertDialog, activity: Activity) {
        runCatching {
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
            )
        }
    }

    @Volatile
    private var writer: SettingsWriter? = null

    /** [currentDialog] 挂靠的 Activity:宿主 Activity 被直接销毁(无 dismiss 回调)时用来识别 stale 弹窗。 */
    @Volatile
    private var currentActivity: Activity? = null

    /** 当前正在显示的弹窗（主页面或详情页）：防止连点导致弹窗层层叠加。 */
    @Volatile
    private var currentDialog: AlertDialog? = null

    fun show(activity: Activity, onDismiss: (() -> Unit)? = null) {
        // Activity 已结束/正在结束时不能再 show()，否则会 WindowManager$BadTokenException
        if (activity.isFinishing || activity.isDestroyed) return
        // 上一个弹窗的挂靠 Activity 已销毁(没有 dismiss 回调):清掉静态引用并释放 writer,
        // 否则这里会永远命中「已在显示」而拒绝打开,还泄漏死 Activity。
        // writer 一并重建:复用 stale writer 会跳过 hydrate,期间模块端改过的设置会以旧值展示。
        if (currentActivity?.let { it.isFinishing || it.isDestroyed } == true) {
            currentDialog = null
            currentActivity = null
            writer?.close()
            writer = null
        }
        // 已经有一个弹窗在显示时不叠加（宿主「我的」页入口可能被连点）
        if (currentDialog?.isShowing == true) return
        if (writer == null) {
            writer = SettingsWriter(activity)
        }
        showMain(activity, onDismiss)
    }

    private fun prefs(activity: Activity): SharedPreferences =
        (writer ?: SettingsWriter(activity).also { writer = it }).sharedPreferences

    private fun showMain(activity: Activity, onDismiss: (() -> Unit)?) {
        if (activity.isFinishing || activity.isDestroyed) return
        val navigating = booleanArrayOf(false)
        val dialogRef = arrayOfNulls<AlertDialog>(1)
        // 统一的前进导航:先 dismiss 当前根弹窗再 show 子弹窗,避免短暂双弹窗叠加
        fun forward(show: () -> Unit) {
            navigating[0] = true
            dialogRef[0]?.dismiss()
            show()
        }
        val root = SettingsScreenBuilder.buildMain(
            activity,
            onSponsorBlockClick = { forward { showDetail(activity, onDismiss) } },
            onEnhanceClick = { forward { showEnhance(activity, onDismiss) } },
        )

        // 控制中心:主弹窗的专名(2026-09-19 起,不再与模块名 Bili2233 混用)。
        // 标题即内容里的品牌粉头图;不再用 AlertDialog 原生标题/按钮 ——
        // 透明窗口下它们会露出宿主主题的深色样式(2026-09-19 截图回归)。
        val dialog = AlertDialog.Builder(activity)
            .setView(SettingsScreenBuilder.wrapScroll(activity, root))
            .create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            if (dialogRef[0] === currentDialog) {
                currentDialog = null
                currentActivity = null
            }
            if (!navigating[0]) {
                // 根弹窗真正被关闭：释放 writer，避免静态引用长期持有旧 Activity 的 Context
                writer?.close()
                writer = null
                onDismiss?.invoke()
            }
        }
        dialogRef[0] = dialog
        currentDialog = dialog
        currentActivity = activity
        applyRoundedWindow(dialog, activity)
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
            .setView(
                SettingsScreenBuilder.detailPage(
                    activity, "SponsorBlock", onBack = { back() },
                    SettingsScreenBuilder.buildDetail(activity, prefs(activity)),
                )
            )
            .create()
        dialog.setOnCancelListener { back() }
        dialog.setOnDismissListener {
            // 只有当前登记的弹窗才清空，避免导航时把新弹窗的登记清掉
            if (dialogRef[0] === currentDialog) {
                currentDialog = null
                currentActivity = null
            }
        }
        dialogRef[0] = dialog
        currentDialog = dialog
        currentActivity = activity
        applyRoundedWindow(dialog, activity)
        dialog.show()
    }

    /** 「B 站增强」详情弹窗(与 showDetail 同一套导航语义,返回键/「返回」回主页)。 */
    private fun showEnhance(activity: Activity, onDismiss: (() -> Unit)?) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialogRef = arrayOfNulls<AlertDialog>(1)
        fun back() {
            dialogRef[0]?.currentFocus?.clearFocus()
            // 先 dismiss 当前 detail 弹窗再导航:onClick 返回后 AOSP 会自动 dismiss,
            // 直接 showMain 会造成短暂双弹窗叠加(与 showDetail 的导航写法保持一致)
            dialogRef[0]?.dismiss()
            showMain(activity, onDismiss)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(
                SettingsScreenBuilder.detailPage(
                    activity, "B 站增强", onBack = { back() },
                    SettingsScreenBuilder.buildEnhance(activity, prefs(activity)),
                )
            )
            .create()
        dialog.setOnCancelListener { back() }
        dialog.setOnDismissListener {
            // 只有当前登记的弹窗才清空，避免导航时把新弹窗的登记清掉
            if (dialogRef[0] === currentDialog) {
                currentDialog = null
                currentActivity = null
            }
        }
        dialogRef[0] = dialog
        currentDialog = dialog
        currentActivity = activity
        applyRoundedWindow(dialog, activity)
        dialog.show()
    }
}
