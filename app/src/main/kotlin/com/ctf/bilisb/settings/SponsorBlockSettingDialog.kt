package com.ctf.bilisb.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.ctf.bilisb.BuildConfig
import com.ctf.bilisb.model.SponsorCategories
import com.ctf.bilisb.sponsor.SkipStatsStore
import com.ctf.bilisb.sponsor.UserIdentityStore

/**
 * Bili2233 设置对话框(纯代码,不依赖 XML / PreferenceFragment)。
 *
 * 结构:主页只有一个「SponsorBlock」入口,点开进入详情页(单页平铺,小标题分隔:
 * 自动跳过 → 跳过类别 → 标记颜色 → 界面显示 → 统计 → 服务器),「返回」回主页。
 * 详情页离开前 clearFocus,把数字/服务器输入框的最后编辑强制提交,避免直接点按钮丢值。
 *
 * 镜像:全程持有一个常驻 [SettingsWriter](监听 SharedPreferences 变更并镜像成 Hook 端
 * 可读的 JSON)。放在对象字段里防止被 GC —— 否则监听是弱引用,writer 一旦回收,变更镜像
 * 会静默失效。
 */
object SponsorBlockSettingDialog {

    @Volatile
    private var writer: SettingsWriter? = null

    private const val REPO_URL = "https://github.com/ch6vip/lsposed-bili-sponsorblock"

    /** 「关于」→「更新」展示的更新摘要(最新在上)。发版时手动维护。 */
    private val CHANGELOG = listOf(
        "新增「关于」页(版本 / 作者 / 更新)",
        "设置界面改为单入口 + 详情页",
        "新增 片段统计(已跳过时长累计)",
        "新增 分类标记颜色自定义 + 颜色选择器",
        "新增 片段静音 / 倒计时取消 / 手动跳过 / 最小片段时长过滤",
    )

    fun show(activity: Activity, onDismiss: (() -> Unit)? = null) {
        writer = SettingsWriter(activity)
        showMain(activity, onDismiss)
    }

    private fun prefs(activity: Activity): SharedPreferences =
        (writer ?: SettingsWriter(activity).also { writer = it }).sharedPreferences

    // ===================== 主页(单一入口) =====================

    private fun showMain(activity: Activity, onDismiss: (() -> Unit)?) {
        val navigating = booleanArrayOf(false)
        val dialogRef = arrayOfNulls<AlertDialog>(1)

        val root = column(activity)
        root.addView(entryRow(activity, "SponsorBlock", "赞助/片头等片段的跳过与标记设置") {
            navigating[0] = true
            dialogRef[0]?.dismiss()
            showDetail(activity, onDismiss)
        })

        // 关于(平铺在主页 SponsorBlock 入口下面)
        root.addView(sectionTitle(activity, "关于"))
        root.addView(aboutItem(activity, "版本", "${BuildConfig.VERSION_NAME}(versionCode ${BuildConfig.VERSION_CODE})"))
        root.addView(aboutItem(activity, "作者", "ch6vip\ngithub.com/ch6vip/lsposed-bili-sponsorblock"))
        root.addView(aboutItem(activity, "更新", CHANGELOG.joinToString("\n") { "· $it" }) {
            openUrl(activity, REPO_URL)
        })

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Bili2233")
            .setView(wrapScroll(activity, root))
            .setPositiveButton("关闭", null)
            .create()
        dialog.setOnDismissListener { if (!navigating[0]) onDismiss?.invoke() }
        dialogRef[0] = dialog
        dialog.show()
    }

    // ===================== 详情页(全部设置) =====================

    private fun showDetail(activity: Activity, onDismiss: (() -> Unit)?) {
        val p = prefs(activity)
        val root = column(activity)

        // 总开关
        root.addView(createCheckBox(activity, p, SettingsKeys.ENABLED, "启用 SponsorBlock", "关闭后模块不工作", true))

        // 自动跳过
        root.addView(sectionTitle(activity, "自动跳过"))
        root.addView(createCheckBox(activity, p, SettingsKeys.AUTO_SKIP, "自动跳过", "检测到片段时自动跳过", true))
        root.addView(createCheckBox(activity, p, SettingsKeys.MANUAL_SKIP, "手动跳过", "片段内显示跳过按钮,点按才跳(覆盖自动跳过)", false))
        root.addView(createCheckBox(activity, p, SettingsKeys.MUTE_SEGMENTS, "片段静音", "对 mute 类片段静音而非跳过", false))
        root.addView(numberRow(activity, p, SettingsKeys.MIN_SKIP_DURATION, "最小片段时长(秒)："))
        root.addView(numberRow(activity, p, SettingsKeys.SKIP_COUNTDOWN, "自动跳过倒计时(秒)："))

        // 跳过类别
        root.addView(sectionTitle(activity, "跳过类别"))
        val categories = listOf(
            Triple(SettingsKeys.CAT_SPONSOR, "赞助/恰饭", "付费推广、赞助商"),
            Triple(SettingsKeys.CAT_SELFPROMO, "自我推广", "自己的商品、链接"),
            Triple(SettingsKeys.CAT_INTERACTION, "互动提醒", "点赞、关注提示"),
            Triple(SettingsKeys.CAT_INTRO, "开场动画", "片头动画"),
            Triple(SettingsKeys.CAT_OUTRO, "结束画面", "片尾鸣谢"),
            Triple(SettingsKeys.CAT_PREVIEW, "回顾/概要", "前情回顾"),
            Triple(SettingsKeys.CAT_MUSIC_OFFTOPIC, "非音乐片段", "MV中非音乐部分"),
            Triple(SettingsKeys.CAT_FILLER, "填充内容", "笑话、重复片段"),
            Triple(SettingsKeys.CAT_POI_HIGHLIGHT, "精彩时刻", "视频精彩部分"),
        )
        for ((key, title, summary) in categories) {
            root.addView(createCheckBox(activity, p, key, title, summary, true))
        }

        // 标记颜色
        root.addView(sectionTitle(activity, "标记颜色"))
        root.addView(hint(activity, "点击色块自定义各分类在进度条上的标记颜色"))
        for ((category, name) in SponsorCategories.displayNames) {
            root.addView(ColorPickerDialog.colorRow(activity, p, category, name))
        }

        // 界面显示
        root.addView(sectionTitle(activity, "界面显示"))
        root.addView(createCheckBox(activity, p, SettingsKeys.SHOW_TOAST, "跳过提示", "跳过时显示 Toast", true))
        root.addView(createCheckBox(activity, p, SettingsKeys.SHOW_SEEKBAR_MARKER, "进度条标记", "标记片段位置", true))
        root.addView(createCheckBox(activity, p, SettingsKeys.SHOW_TIME_DEDUCTION, "时间扣减", "总时长减去跳过时长", true))
        root.addView(createCheckBox(activity, p, SettingsKeys.SHOW_SUBMIT_BUTTON, "标记按钮", "播放器内显示提交按钮", true))

        // 统计
        root.addView(sectionTitle(activity, "统计"))
        buildStats(activity, root)

        // 提交配置
        root.addView(sectionTitle(activity, "提交配置"))
        root.addView(userIdRow(activity, p))
        root.addView(defaultSubmitCategoryRow(activity, p))

        // 服务器
        root.addView(sectionTitle(activity, "服务器"))
        root.addView(serverRow(activity, p))
        root.addView(numberRow(activity, p, SettingsKeys.CACHE_TTL_MINUTES, "缓存 TTL(分钟)："))

        val dialogRef = arrayOfNulls<AlertDialog>(1)
        fun back() {
            dialogRef[0]?.currentFocus?.clearFocus() // 提交数字/服务器输入框的最后编辑
            showMain(activity, onDismiss)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("SponsorBlock")
            .setView(wrapScroll(activity, root))
            .setPositiveButton("返回") { _, _ -> back() }
            .create()
        dialog.setOnCancelListener { back() } // 返回键/点外部:回主页
        dialogRef[0] = dialog
        dialog.show()
    }

    // ===================== item builders =====================

    /** 统计区:总跳过数 / 节省时长 + 分类明细 + 重置。与跳过逻辑同进程,直接读单例。 */
    private fun buildStats(activity: Activity, parent: LinearLayout) {
        val summary = TextView(activity).apply {
            textSize = 15f
            setPadding(0, dp(activity, 4), 0, dp(activity, 4))
        }
        val detail = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(activity, 8))
        }

        fun refresh() {
            val s = SkipStatsStore.snapshot()
            summary.text = "已跳过 ${s.totalCount} 个片段 · 共节省 ${formatDuration(s.totalDurationMs)}"
            detail.text = if (s.perCategory.isEmpty()) {
                "暂无记录"
            } else {
                s.perCategory.entries.joinToString("\n") { (cat, st) ->
                    "  ${SponsorCategories.displayName(cat)}：${st.count} 个 · ${formatDuration(st.durationMs)}"
                }
            }
        }
        refresh()

        parent.addView(summary)
        parent.addView(detail)
        parent.addView(Button(activity).apply {
            text = "重置统计"
            setOnClickListener {
                SkipStatsStore.reset()
                refresh()
                Toast.makeText(activity, "统计已重置", Toast.LENGTH_SHORT).show()
            }
        })
    }

    /** ms → "M:SS" 或 "H:MM:SS"。 */
    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun createCheckBox(
        activity: Activity,
        prefs: SharedPreferences,
        key: String,
        title: String,
        summary: String,
        defaultValue: Boolean,
    ): LinearLayout {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        }
        val checkBox = CheckBox(activity).apply {
            text = title
            textSize = 15f
            isChecked = prefs.getBoolean(key, defaultValue)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(key, isChecked).apply()
            }
        }
        val summaryView = TextView(activity).apply {
            text = summary
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(checkBox.paddingLeft + dp(activity, 32), 0, 0, 0)
        }
        layout.addView(checkBox)
        layout.addView(summaryView)
        return layout
    }

    /**
     * 数字(秒)输入行。失焦时规整为非负数并存回;返回主页 / 关闭前的 clearFocus
     * 也会触发本回调,避免丢失最后编辑。
     */
    private fun numberRow(activity: Activity, prefs: SharedPreferences, key: String, label: String): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(activity, 10), 0, dp(activity, 10))
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = label
                textSize = 14f
            })
            addView(EditText(activity).apply {
                setText(prefs.getString(key, "0"))
                hint = "0"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val normalized = (text.toString().trim().toFloatOrNull()?.coerceAtLeast(0f) ?: 0f).toString()
                        setText(normalized)
                        prefs.edit().putString(key, normalized).apply()
                    }
                }
            })
        }
    }

    /** 服务器地址行:失焦时保存,空串不覆盖(避免清空导致请求失败)。 */
    private fun serverRow(activity: Activity, prefs: SharedPreferences): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(activity, 10), 0, dp(activity, 10))
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "服务器地址："
                textSize = 14f
            })
            addView(EditText(activity).apply {
                setText(prefs.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER))
                hint = SettingsKeys.DEFAULT_SERVER
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val addr = text.toString().trim()
                        if (addr.isNotEmpty()) {
                            prefs.edit().putString(SettingsKeys.SERVER_ADDRESS, addr).apply()
                        } else {
                            setText(prefs.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER))
                        }
                    }
                }
            })
        }
    }

    private fun defaultSubmitCategoryRow(activity: Activity, prefs: SharedPreferences): View {
        fun currentCategory(): String {
            val saved = prefs.getString(SettingsKeys.DEFAULT_SUBMIT_CATEGORY, SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE)
            return if (saved in SponsorCategories.displayNames) saved ?: SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE else SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE
        }

        val valueView = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(activity, 2), 0, 0)
        }
        fun refresh() {
            valueView.text = SponsorCategories.displayName(currentCategory())
        }
        refresh()

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 10), 0, dp(activity, 10))
            isClickable = true
            background = selectableItemBackground(activity)

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(activity).apply {
                    text = "默认标记类别"
                    textSize = 16f
                    setTextColor(Color.parseColor("#212121"))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(valueView)
            })
            addView(TextView(activity).apply {
                text = "›"
                textSize = 24f
                setTextColor(Color.GRAY)
            })
            setOnClickListener {
                val categories = SponsorCategories.displayNames.keys.toList()
                val labels = categories.map { SponsorCategories.displayName(it) }.toTypedArray()
                val index = categories.indexOf(currentCategory()).coerceAtLeast(0)
                AlertDialog.Builder(activity)
                    .setTitle("默认标记类别")
                    .setSingleChoiceItems(labels, index) { dialog, which ->
                        prefs.edit().putString(SettingsKeys.DEFAULT_SUBMIT_CATEGORY, categories[which]).apply()
                        refresh()
                        dialog.dismiss()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun userIdRow(activity: Activity, prefs: SharedPreferences): View {
        fun currentUserId(): String {
            val saved = prefs.getString(SettingsKeys.USER_ID, "")
            if (UserIdentityStore.isValidUserId(saved)) return saved ?: ""
            val generated = UserIdentityStore.generateUserId()
            prefs.edit().putString(SettingsKeys.USER_ID, generated).apply()
            return generated
        }

        val valueView = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(activity, 2), 0, 0)
        }
        fun refresh() {
            valueView.text = currentUserId()
        }
        refresh()

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 10), 0, dp(activity, 10))
            addView(TextView(activity).apply {
                text = "用户 ID"
                textSize = 16f
                setTextColor(Color.parseColor("#212121"))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(valueView)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(activity).apply {
                    text = "复制"
                    setOnClickListener {
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Bili2233 userId", currentUserId()))
                        Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
                    }
                })
                addView(Button(activity).apply {
                    text = "重置"
                    setOnClickListener {
                        prefs.edit().putString(SettingsKeys.USER_ID, UserIdentityStore.generateUserId()).apply()
                        refresh()
                        Toast.makeText(activity, "已重置", Toast.LENGTH_SHORT).show()
                    }
                })
                addView(Button(activity).apply {
                    text = "导入"
                    setOnClickListener {
                        showUserIdImportDialog(activity, prefs, ::refresh)
                    }
                })
            })
        }
    }

    private fun showUserIdImportDialog(activity: Activity, prefs: SharedPreferences, onSaved: () -> Unit) {
        val edit = EditText(activity).apply {
            setText(prefs.getString(SettingsKeys.USER_ID, ""))
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
        }
        AlertDialog.Builder(activity)
            .setTitle("导入用户 ID")
            .setView(edit)
            .setPositiveButton("保存") { _, _ ->
                val userId = edit.text.toString().trim()
                if (!UserIdentityStore.isValidUserId(userId)) {
                    Toast.makeText(activity, "用户 ID 必须是 32 位十六进制", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.edit().putString(SettingsKeys.USER_ID, userId).apply()
                onSaved()
                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===================== UI 小工具 =====================

    /** 主页入口行:标题 + 摘要 + 右侧 "›",整行可点,带主题水波纹。 */
    private fun entryRow(activity: Activity, title: String, summary: String, onClick: () -> Unit): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 16), 0, dp(activity, 16))
            isClickable = true
            background = selectableItemBackground(activity)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(activity).apply {
                    text = title
                    textSize = 18f
                    setTextColor(Color.parseColor("#FF6699"))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(activity).apply {
                    text = summary
                    textSize = 12f
                    setTextColor(Color.GRAY)
                })
            })
            addView(TextView(activity).apply {
                text = "›"
                textSize = 24f
                setTextColor(Color.GRAY)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun sectionTitle(activity: Activity, title: String): TextView = TextView(activity).apply {
        text = title
        textSize = 16f
        setTextColor(Color.parseColor("#FF6699"))
        setPadding(0, dp(activity, 24), 0, dp(activity, 8))
        setTypeface(typeface, Typeface.BOLD)
    }

    /**
     * 「关于」里的条目:粗体标题 + 灰色多行内容(对齐参考图)。
     * 传 [onClick] 则整行可点(带水波纹 + 右侧 "›"),用于「更新」跳转项目主页。
     */
    private fun aboutItem(activity: Activity, title: String, value: String, onClick: (() -> Unit)? = null): View {
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 16f
                setTextColor(Color.parseColor("#212121"))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = value
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, dp(activity, 2), 0, 0)
            })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 10), 0, dp(activity, 10))
            textCol.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(textCol)
            if (onClick != null) {
                isClickable = true
                background = selectableItemBackground(activity)
                addView(TextView(activity).apply {
                    text = "›"
                    textSize = 24f
                    setTextColor(Color.GRAY)
                })
                setOnClickListener { onClick() }
            }
        }
    }

    private fun openUrl(activity: Activity, url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(activity, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun column(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 8))
    }

    private fun wrapScroll(activity: Activity, child: View): ScrollView = ScrollView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(child)
    }

    private fun hint(activity: Activity, text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.GRAY)
        setPadding(0, 0, 0, dp(activity, 4))
    }

    private fun selectableItemBackground(activity: Activity): android.graphics.drawable.Drawable? {
        val tv = TypedValue()
        return if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            activity.resources.getDrawable(tv.resourceId, activity.theme)
        } else {
            null
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
