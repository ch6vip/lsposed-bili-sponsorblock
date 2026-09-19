package com.ctf.bilisb.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * 播放器内的底部半屏面板（bottom sheet 样式）。
 *
 * ## 设计取舍
 * 1. **被动 UI**：本文件不 import `sponsor` / `settings` 包下的任何东西，只吃一个数据快照
 *    [PlayerSheetState] + 一组回调 [Callbacks]。这样面板可以被「模块设置页」和「宿主播放页」
 *    复用，也不会因为 controller 的方法签名变动而连带编译失败。
 * 2. **零依赖**：只用 `android.widget.*` / `android.view.*` / `android.graphics.*`。
 *    项目里没有 androidx/material，`BottomSheetDialog` 用不了，所以是
 *    `Dialog` + `window.setGravity(Gravity.BOTTOM)` 手工搭出来的。
 * 3. **深色模式**：卡片底色仍取浅色（贴合参考截图的信息层级），但标题色走
 *    [primaryTextColor] 从主题解析，而不是硬编码 `#212121`——宿主播放页在深色主题下
 *    `textColorPrimary` 是浅色，若强行用深色文字会贴在浅卡片上，可读性由「浅卡片」保证。
 */
object SponsorBlockPlayerSheet {

    private const val TAG = "com.ctf.bilisb.player_sheet"

    /** B 站品牌粉：开关选中态 / 强调文字。 */
    private const val BRAND_PINK = 0xFFFB7299.toInt()

    /** 卡片圆角顶部背景色（贴合截图里的浅色卡片）。 */
    private const val CARD_BG = 0xFFF7F7F9.toInt()

    /** 右侧说明/数值的灰色。 */
    private const val VALUE_GRAY = 0xFF999999.toInt()

    /** 分割线颜色。 */
    private const val DIVIDER = 0xFFEEEEEE.toInt()

    /** 行高 52dp。 */
    private const val ROW_HEIGHT_DP = 52

    /** 右侧说明文本最多占的宽度：避免长文案（服务信息）把左侧标题挤没。 */
    private const val VALUE_MAX_WIDTH_DP = 210

    /** 面板内数值编辑的默认上限(秒)。具体行的上限由 [PlayerSheetState.minSkipDurationMaxSec] 传入,
     *  与 SettingsKeys 里各字段自己的 MAX_* 对齐 —— 不能一刀切 600:最短片段时长在设置页允许到 3600。 */
    private const val DEFAULT_MAX_EDIT_NUMBER = 600f

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 当前显示的面板。静态引用 + `isShowing` 双判定做去重：
     * 宿主「我的」页入口/播放器长按都可能被连点，叠两层面板后只有最上面那层能收事件，
     * 底下那层会变成永远关不掉的幽灵。
     */
    @Volatile
    private var current: Dialog? = null

    /** [current] 挂靠的 Activity:宿主播放页被直接销毁(无 dismiss 回调)时用它识别 stale 面板。 */
    @Volatile
    private var currentOwnerActivity: Activity? = null

    /**
     * 展示面板。
     *
     * @return 是否真的展示了。以下情况返回 false：不在主线程、Activity 正在结束/已销毁、
     *         window 不可用、已有面板在显示、构建或 show 抛异常。
     */
    fun show(
        activity: Activity,
        state: PlayerSheetState,
        callbacks: Callbacks,
        log: (String) -> Unit = {},
    ): Boolean {
        // 必须主线程：返回布尔值要有意义就不能异步 post 之后再告诉调用方「成功」，
        // 否则调用方（Hook 侧）可能在 panel 还没建起来时就去改自己的状态。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            log("player sheet show rejected: not on main thread")
            return false
        }
        if (activity.isFinishing || isDestroyed(activity)) {
            log("player sheet show rejected: activity finishing/destroyed")
            return false
        }
        // 上一个面板的挂靠 Activity 已经销毁(宿主直接 finish,没有 dismiss 回调):
        // 此时 isShowing 仍为 true,不清掉的话本面板永远被拒 + 静态引用泄漏死 Activity。
        val stale = current
        val staleOwner = currentOwnerActivity
        if (stale != null && staleOwner != null && (staleOwner.isFinishing || isDestroyed(staleOwner))) {
            runCatching { stale.dismiss() }
            current = null
            currentOwnerActivity = null
        }
        if (current?.isShowing == true) {
            log("player sheet show rejected: already showing")
            return false
        }

        return runCatching {
            val dialog = Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val content = buildContent(activity, state, callbacks, dialog, log)
            dialog.setContentView(content)
            dialog.setCanceledOnTouchOutside(true)

            val window = dialog.window
            if (window == null) {
                log("player sheet show failed: window is null")
                return@runCatching false
            }
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setGravity(Gravity.BOTTOM)
            // 宽 MATCH_PARENT + 高 WRAP_CONTENT：高度由内容决定，超过屏幕时由内部的
            // MaxHeightScrollView 截断并滚动（半屏面板不该盖住整个播放器）。
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setDimAmount(0.5f)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 状态栏/导航栏不跟着变暗，减少对宿主播放器沉浸式状态栏的干扰
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

            dialog.setOnDismissListener {
                // 只有当前登记的还是自己时才清空：避免把后来者的登记清掉
                if (current === dialog) {
                    current = null
                    currentOwnerActivity = null
                }
                runCatching { callbacks.onDismiss() }
                    .onFailure { log("player sheet onDismiss callback failed: ${it.javaClass.name}") }
            }

            dialog.show()
            current = dialog
            currentOwnerActivity = activity
            // 出入场：从屏幕底部滑入，替代没有的 BottomSheetBehavior。
            slideIn(dialog, content)
            true
        }.onFailure {
            log("player sheet show failed: ${it.javaClass.name}: ${it.message}")
        }.getOrDefault(false)
    }

    /** 主动关闭（宿主播放页 onPause / 切换视频时调用），会触发 [Callbacks.onDismiss]。 */
    fun dismiss(log: (String) -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { dismiss(log) }
            return
        }
        runCatching { current?.dismiss() }
            .onFailure { log("player sheet dismiss failed: ${it.javaClass.name}: ${it.message}") }
    }

    /** 是否有面板正在显示（供 Hook 侧判断要不要拦截返回键等）。 */
    fun isShowing(): Boolean = current?.isShowing == true

    // ---------------------------------------------------------------- 内容构建

    private fun buildContent(
        activity: Activity,
        state: PlayerSheetState,
        callbacks: Callbacks,
        dialog: Dialog,
        log: (String) -> Unit,
    ): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // 只用顶部圆角：底部与屏幕边缘齐平，这就是 bottom sheet 的视觉特征
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(CARD_BG)
                cornerRadii = floatArrayOf(
                    dp(activity, 18).toFloat(), dp(activity, 18).toFloat(),
                    dp(activity, 18).toFloat(), dp(activity, 18).toFloat(),
                    0f, 0f, 0f, 0f,
                )
            }
        }
        card.addView(dragHandle(activity))

        val scroll = MaxHeightScrollView(activity).apply {
            // 上限 70% 屏高：剩下的 30% 留给可见的播放画面与遮罩点击区
            maxHeight = (activity.resources.displayMetrics.heightPixels * 0.7f).toInt()
            isFillViewport = false
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(activity, 12))
        }

        // ---- 第一组：信息与操作 ----
        body.addView(row(activity, "🎬", "片段信息", valueText(activity,
            SheetStateFormatter.formatSegmentInfo(state.segmentCount, state.insideSegmentLabel))))
        body.addView(row(activity, "🎯", "空降助手", switchBox(activity, state.autoSkipEnabled) { on ->
            callbacks.onToggleAutoSkip(on)
        }))
        body.addView(row(activity, "📮", "提交片段", valueText(activity, state.submitHint)) {
            callbacks.onSubmitSegment()
        })
        body.addView(row(activity, "⏭", "手动跳过", valueText(activity, state.manualSkipSummary)) {
            showManualSkipPicker(activity, state.manualSegments, callbacks, log)
        })
        body.addView(row(activity, "🔄", "刷新片段", valueText(activity, "重新拉取当前视频片段")) {
            callbacks.onRefreshSegments()
        })

        body.addView(divider(activity))

        // ---- 第二组：设置项 ----
        body.addView(row(activity, "📊", "服务信息", valueText(activity, state.serviceStatus)))
        body.addView(row(activity, "🔔", "显示跳过 Toast", switchBox(activity, state.showToast) { on ->
            callbacks.onToggleShowToast(on)
        }))
        body.addView(row(activity, "🎨", "显示进度条片段", switchBox(activity, state.showSeekbarMarker) { on ->
            callbacks.onToggleSeekbarMarker(on)
        }))
        body.addView(row(activity, "📈", "跳过次数统计", switchBox(activity, state.showSkipStats) { on ->
            callbacks.onToggleSkipStats(on)
        }))
        body.addView(row(activity, "⏱", "最短片段时长", valueText(activity, "${state.minSkipDurationLabel} ›")) {
            // 回调按约定的签名不带值：面板不掌握持久化，弹窗里的输入通过可选接口
            // ValueEditingCallbacks 额外回传，调用方按自己支持的能力决定实现哪个。
            editNumber(activity, "最短片段时长（秒）", state.minSkipDurationLabel, dialog, log,
                maxValue = state.minSkipDurationMaxSec) { value ->
                (callbacks as? ValueEditingCallbacks)?.onMinSkipDurationEdited(value)
                callbacks.onEditMinSkipDuration()
            }
        })
        body.addView(row(activity, "👤", "用户 ID", valueText(activity,
            "${SheetStateFormatter.formatUserId(state.userIdLabel)} ›")) {
            editText(activity, "用户 ID", state.userIdLabel, dialog, log) { value ->
                (callbacks as? ValueEditingCallbacks)?.onUserIdEdited(value)
                callbacks.onEditUserId()
            }
        })

        scroll.addView(body)
        card.addView(scroll)
        return card
    }

    /**
     * 拖拽条。纯装饰（不实现真正的拖拽手势）：真正的拖拽需要
     * `View.OnTouchListener` + `window.setLayout` 实时改高，收益低且容易和宿主播放器的
     * 手势冲突；这里保留视觉锚点，点击遮罩/返回键仍然是唯一关闭路径。
     */
    private fun dragHandle(activity: Activity): View {
        val pill = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 4))
            background = GradientDrawable().apply {
                setColor(0xFFDDDDDD.toInt())
                cornerRadius = dp(activity, 2).toFloat()
            }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
            addView(pill)
        }
    }

    /** 一行：`图标 + 标题 + 右侧内容`，高度固定 52dp。 */
    private fun row(
        activity: Activity,
        icon: String,
        title: String,
        right: View?,
        onClick: (() -> Unit)? = null,
    ): View {
        val titleView = TextView(activity).apply {
            text = title
            textSize = 15f
            setTextColor(primaryTextColor(activity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, ROW_HEIGHT_DP)
            setPadding(dp(activity, 18), 0, dp(activity, 18), 0)
            // 图标用 emoji 占位而不是 drawable 资源：本模块没有 res/ 下的图标资产，
            // 也不允许引三方图标库。正式版可换成 VectorDrawable 并 setCompoundDrawables。
            addView(TextView(activity).apply {
                text = icon
                textSize = 16f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(activity, 28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(titleView)
            if (right != null) {
                right.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(activity, 8) }
                addView(right)
            }
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                background = selectableItemBackground(activity)
                setOnClickListener { runCatching { onClick() } }
            }
        }
    }

    /** 右侧灰色说明/数值。 */
    private fun valueText(activity: Activity, text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setTextColor(VALUE_GRAY)
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        maxWidth = dp(activity, VALUE_MAX_WIDTH_DP)
    }

    /**
     * 右侧开关。
     *
     * 直接用框架 [Switch]（不是 androidx `SwitchCompat`）：模块里没有 androidx。
     * 选中色用 `buttonTintList` 涂成 B 站粉；`buttonTint` 对 Switch 的具体着色效果随
     * 宿主主题的 switchStyle 而定，所以外面再包一层 `runCatching`——真机上万一
     * 某些 ROM 的自定义 Switch 不认这个 tint，也只是回落成主题默认色，不能因此让整个面板崩掉。
     *
     * 注意 `setOnCheckedChangeListener` 要在设置 `isChecked` **之后**注册，
     * 否则面板一打开就会把初始值当成一次用户操作回放给调用方（写盘 + 重拉片段）。
     */
    private fun switchBox(activity: Activity, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val switch = Switch(activity).apply {
            isChecked = checked
            runCatching {
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(BRAND_PINK, 0xFFCCCCCC.toInt()),
                )
            }
        }
        switch.setOnCheckedChangeListener { _, isChecked -> runCatching { onChange(isChecked) } }
        return switch
    }

    private fun divider(activity: Activity): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 1),
        ).apply { setMargins(dp(activity, 18), dp(activity, 4), dp(activity, 18), dp(activity, 4)) }
        setBackgroundColor(DIVIDER)
    }

    // ---------------------------------------------------------------- 子弹窗

    /**
     * 「手动跳过」的片段选择列表。
     *
     * 用 AlertDialog + setItems 而不是在面板内展开：面板本体是 WRAP_CONTENT 高度的，
     * 展开后会突然长高一截，而且两个列表混在一起容易点错。
     */
    private fun showManualSkipPicker(
        activity: Activity,
        segments: List<ManualSegmentItem>,
        callbacks: Callbacks,
        log: (String) -> Unit,
    ) {
        if (segments.isEmpty()) {
            toast(activity, "当前视频没有可跳过的片段")
            return
        }
        if (activity.isFinishing || isDestroyed(activity)) return
        runCatching {
            val labels = segments.map { it.label }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle("选择要跳到的片段")
                .setItems(labels) { _, which ->
                    val item = segments.getOrNull(which) ?: return@setItems
                    runCatching { callbacks.onManualSkip(item) }
                        .onFailure { log("manual skip callback failed: ${it.javaClass.name}") }
                }
                .setNegativeButton("取消", null)
                .show()
        }.onFailure { log("manual skip picker failed: ${it.javaClass.name}: ${it.message}") }
    }

    /**
     * 带 EditText 的数值编辑弹窗。非法输入不关窗、只提示，语义与
     * `SettingsScreenBuilder.numberRow` 的失焦归一化保持一致（夹到 >= 0）。
     */
    private fun editNumber(
        activity: Activity,
        title: String,
        current: String,
        parent: Dialog,
        log: (String) -> Unit,
        maxValue: Float = DEFAULT_MAX_EDIT_NUMBER,
        onConfirm: (Float) -> Unit,
    ) {
        val input = EditText(activity).apply {
            setText(current.removeSuffix("s").trim())
            hint = "0"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSelection(text.length)
        }
        showEditDialog(activity, title, input, parent, log) {
            val parsed = input.text.toString().trim().toFloatOrNull()
            if (parsed == null) {
                toast(activity, "请输入数字")
                false
            } else {
                // 与 SettingsKeys 的 MAX clamp 对齐:非法/超大输入(1e30)不原样回调
                onConfirm(parsed.coerceIn(0f, maxValue))
                true
            }
        }
    }

    /** 带 EditText 的文本编辑弹窗（用户 ID）。 */
    private fun editText(
        activity: Activity,
        title: String,
        current: String,
        parent: Dialog,
        log: (String) -> Unit,
        onConfirm: (String) -> Unit,
    ) {
        val input = EditText(activity).apply {
            setText(current)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }
        showEditDialog(activity, title, input, parent, log) {
            val value = input.text.toString().trim()
            if (value.isEmpty()) {
                toast(activity, "不能为空")
                false
            } else if (!com.ctf.bilisb.sponsor.UserIdentityStore.isValidUserId(value)) {
                toast(activity, "用户 ID 须为 32 位十六进制")
                false
            } else {
                onConfirm(value)
                true
            }
        }
    }

    /**
     * @param onConfirm 返回 true 表示校验通过、可以关窗；false 则保持弹窗打开。
     *                  用 `setPositiveButton(..., null)` + 手动接管点击，理由同 ColorPickerDialog：
     *                  默认实现会无条件 dismiss，非法输入没有重试机会。
     */
    private fun showEditDialog(
        activity: Activity,
        title: String,
        input: EditText,
        parent: Dialog,
        log: (String) -> Unit,
        onConfirm: () -> Boolean,
    ) {
        if (activity.isFinishing || isDestroyed(activity)) return
        runCatching {
            lateinit var dialog: AlertDialog
            // B 站风格:透明窗口 + 白色圆角卡片,标题/按钮画在内容里
            // (原生标题/按钮会露出宿主主题样式,与面板的浅色卡片设计冲突)
            fun textButton(label: String, color: Int, bold: Boolean, onClick: () -> Unit): TextView =
                TextView(activity).apply {
                    text = label
                    textSize = 15f
                    setTextColor(color)
                    setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                    isClickable = true
                    isFocusable = true
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(activity, 20).toFloat()
                        setColor(if (bold) 0x14FB7299.toInt() else Color.TRANSPARENT)
                    }
                    setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 8))
                    setOnClickListener { onClick() }
                }

            dialog = AlertDialog.Builder(activity)
                .setView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Color.WHITE)
                        cornerRadius = dp(activity, 12).toFloat()
                    }
                    setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 8))
                    addView(TextView(activity).apply {
                        text = title
                        textSize = 17f
                        setTextColor(0xFF18191C.toInt())
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(input)
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        addView(textButton("取消", 0xFF61666D.toInt(), false) { dialog.dismiss() })
                        addView(textButton("保存", BRAND_PINK, true) {
                            // 校验与回调都可能抛（调用方写入失败），就地兜住，不然会崩在主线程
                            val ok = runCatching { onConfirm() }
                                .onFailure { log("edit dialog confirm failed: ${it.javaClass.name}: ${it.message}") }
                                .getOrDefault(false)
                            if (ok) {
                                dialog.dismiss()
                                // 值可能已经变了（用户 ID 重新生成 / 时长被夹到 0），
                                // 关掉面板让调用方用最新快照重开，避免残留旧值。
                                runCatching { parent.dismiss() }
                            }
                        })
                    })
                })
                .create()
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
            )
            dialog.show()
        }.onFailure { log("edit dialog failed: ${it.javaClass.name}: ${it.message}") }
    }

    // ---------------------------------------------------------------- 工具

    /** 从屏幕底部滑入。任何一步失败都直接放弃动画（面板已经 show 出来了）。 */
    private fun slideIn(dialog: Dialog, content: View) {
        runCatching {
            val height = dialog.window?.attributes?.height ?: 0
            val distance = if (height > 0) height.toFloat() else
                content.resources.displayMetrics.heightPixels.toFloat() * 0.5f
            content.translationY = distance
            content.alpha = 0.6f
            content.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /**
     * 深色主题下自适应主文字色。
     *
     * 与 `SettingsScreenBuilder.primaryTextColor` 同源取 [android.R.attr.textColorPrimary]，
     * 但 fallback 用 [android.R.attr.colorForeground]（深灰）而不是硬编码 `#212121`：
     * 宿主主题解析不到 textColorPrimary 时还有一个主题级的兜底。
     */
    private fun primaryTextColor(activity: Activity): Int {
        themedColor(activity, android.R.attr.textColorPrimary)?.let { return it }
        themedColor(activity, android.R.attr.colorForeground)?.let { return it }
        return 0xFF212121.toInt()
    }

    private fun themedColor(activity: Activity, attr: Int): Int? {
        val value = TypedValue()
        if (!activity.theme.resolveAttribute(attr, value, true)) return null
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data
        }
        if (value.resourceId != 0) {
            return runCatching { activity.resources.getColor(value.resourceId, activity.theme) }.getOrNull()
        }
        return null
    }

    private fun selectableItemBackground(activity: Activity): android.graphics.drawable.Drawable? {
        val tv = TypedValue()
        return runCatching {
            if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                activity.resources.getDrawable(tv.resourceId, activity.theme)
            } else {
                null
            }
        }.getOrNull()
    }

    private fun toast(activity: Activity, text: String) {
        runCatching { android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show() }
    }

    /** `isDestroyed()` 是 API 17+，minSdk 23 其实够用；用 runCatching 兜住个别 ROM 的实现差异。 */
    private fun isDestroyed(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 &&
            runCatching { activity.isDestroyed }.getOrDefault(false)

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /**
     * 高度受限的 ScrollView。
     *
     * 面板是 WRAP_CONTENT 高度的：行数多 + 小屏/横屏时内容会超过屏幕，被窗口裁掉顶部，
     * 用户就永远看不到「片段信息」那一行。这里在测量阶段把高度夹到屏幕的 70%，
     * 超出的部分交给滚动。
     */
    private class MaxHeightScrollView(context: Context) : ScrollView(context) {
        var maxHeight: Int = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // 父容器给定更小的精确高度时不能测出超过父约束的尺寸:
            // maxHeight 与父约束取 min,再交给 AT_MOST
            val parentLimit = MeasureSpec.getSize(heightMeasureSpec)
            val effective = minOf(maxHeight, parentLimit).coerceAtLeast(0)
            val spec = MeasureSpec.makeMeasureSpec(effective, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, spec)
        }
    }
}

/**
 * 面板展示所需的一次性快照（由调用方从 controller / settings 组装）。
 *
 * 之所以是「一次性快照」而不是可观察状态：面板是模态的，展示期间播放进度会变，
 * 但把这些都做成实时刷新需要一条从 Hook 到面板的观察通道，收益不抵复杂度。
 * 需要更新的场景（切片段、改设置）让调用方 dismiss 后用新快照重开即可。
 */
data class PlayerSheetState(
    /** 当前视频的片段总数。 */
    val segmentCount: Int,
    /** 播放头是否落在某个片段里（[insideSegmentLabel] 非 null 即等价于 true，保留用于显式判断）。 */
    val playheadInsideSegment: Boolean,
    /** 所在片段描述，例如 `"赞助/恰饭 300.0-600.0s"`；不在片段内为 null。 */
    val insideSegmentLabel: String?,
    /** 空降助手（自动跳过）开关状态。 */
    val autoSkipEnabled: Boolean,
    /** 「提交片段」右侧说明，例如 `"标记并提交跳过段"` / `"已标记起点 12.3s"`。 */
    val submitHint: String,
    /** 「手动跳过」右侧说明，例如 `"共 1 个片段 · 点击选择并跳到末尾"`。 */
    val manualSkipSummary: String,
    /** 供选择列表使用的片段。空列表时点击「手动跳过」只提示，不弹空列表。 */
    val manualSegments: List<ManualSegmentItem>,
    /** 「服务信息」右侧说明，例如 `"状态：正常 · 跳过 140 次 · 节省 5521 秒"`。 */
    val serviceStatus: String,
    val showToast: Boolean,
    val showSeekbarMarker: Boolean,
    val showSkipStats: Boolean,
    /** 「最短片段时长」右侧值，例如 `"0.0s"`（建议用 [SheetStateFormatter.formatSeconds] 生成）。 */
    val minSkipDurationLabel: String,
    /** 面板内编辑「最短片段时长」的合法上限(秒)。须与设置页该字段的 MAX 对齐(3600),默认 600 仅为兜底。 */
    val minSkipDurationMaxSec: Float = 600f,
    /** 「用户 ID」右侧值（原始值即可，面板会截断展示）。 */
    val userIdLabel: String,
)

/**
 * 片段选择列表的一项。
 *
 * [label] 由调用方拼好（建议用 [SheetStateFormatter.formatManualSegmentItem]），
 * 面板不再关心分类显示名的来源——那属于 model 层，面板不 import model。
 */
data class ManualSegmentItem(
    val label: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/** 面板 → 调用方的回调。面板自己不写盘、不 seek、不拉网络。 */
interface Callbacks {
    fun onToggleAutoSkip(enabled: Boolean)

    fun onSubmitSegment()

    fun onManualSkip(item: ManualSegmentItem)

    fun onRefreshSegments()

    fun onToggleShowToast(enabled: Boolean)

    fun onToggleSeekbarMarker(enabled: Boolean)

    fun onToggleSkipStats(enabled: Boolean)

    fun onEditMinSkipDuration()

    fun onEditUserId()

    fun onDismiss()
}

/**
 * 可选扩展：想让面板把编辑弹窗里**输入的值**一起带回来时实现它。
 *
 * 不把它并进 [Callbacks] 是为了不强迫所有调用方实现用不到的方法；
 * 实现时 [Callbacks.onEditMinSkipDuration] / [onEditUserId] 仍会被调用一次，
 * 所以只关心「用户点了编辑」的调用方接口保持不变。
 */
interface ValueEditingCallbacks {
    /** 已夹到 `>= 0` 的秒数。 */
    fun onMinSkipDurationEdited(seconds: Float)

    /** 用户输入的原始文本（已 trim、非空）。 */
    fun onUserIdEdited(userId: String)
}
