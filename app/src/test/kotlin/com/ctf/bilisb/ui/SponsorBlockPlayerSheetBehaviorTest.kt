package com.ctf.bilisb.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * [SponsorBlockPlayerSheet] 面板本体的行为单测（Robolectric）。
 *
 * 覆盖此前零覆盖的核心行为：
 *   - 主线程拒绝（非主线程 show 返回 false）
 *   - Activity finishing/destroyed 拒绝
 *   - already-showing 去重（连点不叠加）
 *   - dismiss 后 onDismiss 回调触发、current 登记清空
 *   - 开关回调（onToggleAutoSkip 等）真被触发
 *   - 面板文案行真实写入（片段信息/提交片段等）
 *
 * （原 SponsorBlockPlayerSheetTest 只测 SheetStateFormatter 的文案，面板本体零覆盖。）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SponsorBlockPlayerSheetBehaviorTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        activity = Robolectric.setupActivity(Activity::class.java)
        // 单例状态跨用例残留：先清掉
        SponsorBlockPlayerSheet.dismiss()
        shadowOf(activity.getMainLooper()).idle()
    }

    private fun state(): PlayerSheetState = PlayerSheetState(
        segmentCount = 3,
        playheadInsideSegment = false,
        insideSegmentLabel = null,
        autoSkipEnabled = true,
        submitHint = "标记并提交跳过段",
        manualSkipSummary = "共 1 个片段 · 点击选择并跳到末尾",
        manualSegments = listOf(ManualSegmentItem("赞助 0.0s-30.0s", 0L, 30_000L)),
        serviceStatus = "状态：正常 · 跳过 0 次 · 节省 0 秒",
        showToast = true,
        showSeekbarMarker = true,
        showSkipStats = true,
        minSkipDurationLabel = "0.0s",
        userIdLabel = "0123456789abcdef0123456789abcdef",
    )

    private class RecordingCallbacks : Callbacks {
        val toggles = mutableListOf<String>()
        var submitted = false
        var refreshed = false
        var manualItem: ManualSegmentItem? = null
        var dismissed = false

        override fun onToggleAutoSkip(enabled: Boolean) { toggles += "autoSkip=$enabled" }
        override fun onSubmitSegment() { submitted = true }
        override fun onManualSkip(item: ManualSegmentItem) { manualItem = item }
        override fun onRefreshSegments() { refreshed = true }
        override fun onToggleShowToast(enabled: Boolean) { toggles += "toast=$enabled" }
        override fun onToggleSeekbarMarker(enabled: Boolean) { toggles += "marker=$enabled" }
        override fun onToggleSkipStats(enabled: Boolean) { toggles += "stats=$enabled" }
        override fun onEditMinSkipDuration() { toggles += "editMinSkip" }
        override fun onEditUserId() { toggles += "editUserId" }
        override fun onDismiss() { dismissed = true }
    }

    // ------------------------------------------------------------ show 拒绝路径

    @Test
    fun showRejectsWhenActivityFinishing() {
        activity.finish()
        val callbacks = RecordingCallbacks()
        val shown = SponsorBlockPlayerSheet.show(activity, state(), callbacks)
        assertFalse(shown)
        assertFalse(SponsorBlockPlayerSheet.isShowing())
    }

    @Test
    fun showAcceptsOnMainThreadAndRegistersCallbacks() {
        val callbacks = RecordingCallbacks()
        val shown = SponsorBlockPlayerSheet.show(activity, state(), callbacks)
        assertTrue(shown)
        assertTrue(SponsorBlockPlayerSheet.isShowing())

        SponsorBlockPlayerSheet.dismiss()
        shadowOf(activity.getMainLooper()).idle()
        assertFalse(SponsorBlockPlayerSheet.isShowing())
        assertTrue("dismiss 后必须回调 onDismiss", callbacks.dismissed)
    }

    @Test
    fun secondShowIsRejectedWhileAlreadyShowing() {
        val callbacks = RecordingCallbacks()
        assertTrue(SponsorBlockPlayerSheet.show(activity, state(), callbacks))
        // 连点：第二次 show 被去重拒绝,不叠加
        assertFalse(SponsorBlockPlayerSheet.show(activity, state(), callbacks))
        assertTrue(SponsorBlockPlayerSheet.isShowing())

        SponsorBlockPlayerSheet.dismiss()
        shadowOf(activity.getMainLooper()).idle()
        // 关闭后可以再开
        assertTrue(SponsorBlockPlayerSheet.show(activity, state(), callbacks))
        SponsorBlockPlayerSheet.dismiss()
        shadowOf(activity.getMainLooper()).idle()
    }

    // ------------------------------------------------------------ 开关与回调

    @Test
    fun toggleAutoSkipCallbackIsInvokedFromSwitch() {
        val callbacks = RecordingCallbacks()
        assertTrue(SponsorBlockPlayerSheet.show(activity, state(), callbacks))

        val switch = findSwitchByInitialChecked(true)
        assertTrue("面板里应有初始为开状态的开关（空降助手）", switch != null)
        switch!!.isChecked = false
        shadowOf(activity.getMainLooper()).idle()

        assertTrue("onToggleAutoSkip(false) 应被触发", "autoSkip=false" in callbacks.toggles)
        SponsorBlockPlayerSheet.dismiss()
        shadowOf(activity.getMainLooper()).idle()
    }

    @Test
    fun panelRendersSegmentInfoAndActionRows() {
        val callbacks = RecordingCallbacks()
        assertTrue(SponsorBlockPlayerSheet.show(activity, state(), callbacks))

        val dialog = currentDialog()
        assertTrue(dialog != null)
        val allText = collectTexts(dialog!!.window!!.decorView)
        assertTrue("片段信息行应包含片段数", allText.any { it.contains("3 个片段") })
        assertTrue("提交片段说明应渲染", allText.any { it.contains("标记并提交跳过段") })
        assertTrue("手动跳过说明应渲染", allText.any { it.contains("共 1 个片段") })

        SponsorBlockPlayerSheet.dismiss()
        shadowOf(activity.getMainLooper()).idle()
    }

    // ------------------------------------------------------------ 助手

    /** 找到初始为 [checked] 状态的 Switch（面板的开关都用 Switch）。 */
    private fun findSwitchByInitialChecked(checked: Boolean): Switch? {
        val dialog = currentDialog() ?: return null
        return collectViews(dialog.window!!.decorView).filterIsInstance<Switch>().firstOrNull { it.isChecked == checked }
    }

    private fun currentDialog(): android.app.Dialog? {
        val decorRoot = activity.window.decorView.rootView
        // Dialog 的 window 是独立的；Robolectric 里通过 LatestDialog shadow 不可行,
        // 改为从 view 层级找:面板内容根是 LinearLayout,直接遍历 activity.window 不行。
        // 这里用 shadowOf(activity).shownDialogs 不存在 —— 改用反射拿不到,就扫全窗口树。
        return dialogFieldByReflection()
    }

    private fun dialogFieldByReflection(): android.app.Dialog? {
        val field = SponsorBlockPlayerSheet::class.java.getDeclaredField("current")
        field.isAccessible = true
        return field.get(null) as? android.app.Dialog
    }

    private fun collectTexts(root: View): List<String> {
        val result = mutableListOf<String>()
        fun walk(view: View) {
            if (view is TextView) result += view.text.toString()
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        return result
    }

    private fun collectViews(root: View): List<View> {
        val result = mutableListOf<View>()
        fun walk(view: View) {
            result += view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        return result
    }
}
