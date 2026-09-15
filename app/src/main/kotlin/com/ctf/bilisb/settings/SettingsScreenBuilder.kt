package com.ctf.bilisb.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ctf.bilisb.BuildConfig
import com.ctf.bilisb.model.SponsorCategories
import com.ctf.bilisb.sponsor.SkipStatsStore
import com.ctf.bilisb.sponsor.UserIdentityStore

object SettingsScreenBuilder {
    private const val REPO_URL = "https://github.com/ch6vip/lsposed-bili-sponsorblock"

    private val CHANGELOG = listOf(
        "新增「关于」页(版本 / 作者 / 更新)",
        "设置界面改为单入口 + 详情页",
        "新增 片段统计(已跳过时长累计)",
        "新增 分类标记颜色自定义 + 颜色选择器",
        "新增 片段静音 / 倒计时取消 / 手动跳过 / 最小片段时长过滤",
    )

    fun buildMain(
        activity: Activity,
        showStatusPanel: Boolean = false,
        onSponsorBlockClick: () -> Unit,
    ): LinearLayout {
        return column(activity).apply {
            if (showStatusPanel) {
                addView(statusPanel(activity))
            }
            addView(entryRow(activity, "SponsorBlock", "赞助/片头等片段的跳过与标记设置", onSponsorBlockClick))
            addView(sectionTitle(activity, "关于"))
            addView(aboutItem(activity, "版本", "${BuildConfig.VERSION_NAME}(versionCode ${BuildConfig.VERSION_CODE})"))
            addView(aboutItem(activity, "作者", "ch6vip\ngithub.com/ch6vip/lsposed-bili-sponsorblock"))
            addView(aboutItem(activity, "更新", CHANGELOG.joinToString("\n") { "· $it" }) {
                openUrl(activity, REPO_URL)
            })
        }
    }

    private fun statusPanel(activity: Activity): View {
        val writer = SettingsWriter(activity)
        val prefs = writer.sharedPreferences
        val snapshot = SettingsCodec.snapshotFromPreferences(prefs)
        val userIdState = when {
            UserIdentityStore.isValidUserId(snapshot.userId) -> "已生成"
            snapshot.userId.isBlank() -> "未生成"
            else -> "无效"
        }
        val source = SettingsSyncBridge.readSnapshot(activity)?.let { "Provider" } ?: "本地 SharedPreferences"
        val summary = listOf(
            "模块状态：${if (snapshot.enabled) "已启用" else "已关闭"}",
            "设置来源：$source",
            "服务器：${snapshot.serverAddress}",
            "缓存 TTL：${formatCacheTtl(snapshot.cacheTtlMs)}",
            "用户 ID：$userIdState",
            "启用分类：${snapshot.enabledCategories.size}/${SettingsKeys.CATEGORY_MAP.size}",
        ).joinToString("\n")

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 12), 0, dp(activity, 12))
            addView(TextView(activity).apply {
                text = "状态"
                textSize = 16f
                setTextColor(primaryTextColor(activity))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = summary
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(activity, 6), 0, 0)
            })
        }
    }

    fun buildDetail(activity: Activity, prefs: SharedPreferences): LinearLayout {
        return column(activity).apply {
            addView(createCheckBox(activity, prefs, SettingsKeys.ENABLED, "启用 SponsorBlock", "关闭后模块不工作", true))

            addView(sectionTitle(activity, "自动跳过"))
            addView(createCheckBox(activity, prefs, SettingsKeys.AUTO_SKIP, "自动跳过", "检测到片段时自动跳过", true))
            addView(createCheckBox(activity, prefs, SettingsKeys.MANUAL_SKIP, "手动跳过", "片段内显示跳过按钮,点按才跳(覆盖自动跳过)", false))
            addView(createCheckBox(activity, prefs, SettingsKeys.MUTE_SEGMENTS, "片段静音", "对 mute 类片段静音而非跳过", false))
            addView(numberRow(activity, prefs, SettingsKeys.MIN_SKIP_DURATION, "最小片段时长(秒)：", "0"))
            addView(numberRow(activity, prefs, SettingsKeys.SKIP_COUNTDOWN, "自动跳过倒计时(秒)：", "0"))

            addView(sectionTitle(activity, "跳过类别"))
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
                addView(createCheckBox(activity, prefs, key, title, summary, true))
            }

            addView(sectionTitle(activity, "标记颜色"))
            addView(hint(activity, "点击色块自定义各分类在进度条上的标记颜色"))
            for ((category, name) in SponsorCategories.displayNames) {
                addView(ColorPickerDialog.colorRow(activity, prefs, category, name))
            }

            addView(sectionTitle(activity, "界面显示"))
            addView(createCheckBox(activity, prefs, SettingsKeys.SHOW_TOAST, "跳过提示", "跳过时显示 Toast", true))
            addView(createCheckBox(activity, prefs, SettingsKeys.SHOW_SEEKBAR_MARKER, "进度条标记", "标记片段位置", true))
            addView(createCheckBox(activity, prefs, SettingsKeys.SHOW_TIME_DEDUCTION, "时间扣减", "总时长减去跳过时长", true))
            addView(createCheckBox(activity, prefs, SettingsKeys.SHOW_SKIP_STATS, "跳过次数统计", "累计跳过次数与节省时长", true))
            // 播放器内的「标记按钮」已按需求移除，对应开关不再展示；
            // SettingsKeys.SHOW_SUBMIT_BUTTON 暂时保留以兼容旧配置与编解码。

            addView(sectionTitle(activity, "统计"))
            buildStats(activity, this)

            addView(sectionTitle(activity, "提交配置"))
            addView(userIdRow(activity, prefs))
            addView(defaultSubmitCategoryRow(activity, prefs))

            addView(sectionTitle(activity, "服务器"))
            addView(serverRow(activity, prefs))
            addView(numberRow(activity, prefs, SettingsKeys.CACHE_TTL_MINUTES, "缓存 TTL(分钟)：", SettingsKeys.DEFAULT_CACHE_TTL_MINUTES))
        }
    }

    fun wrapScroll(activity: Activity, child: View): ScrollView = ScrollView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(child)
    }

    private fun buildStats(activity: Activity, parent: LinearLayout) {
        // SkipStatsStore 的数据文件在宿主数据目录(HostTargets.HOST_DATA_DIRS),record 只发生在
        // 宿主进程 —— 模块 APK 进程读不到,这里会永远显示 0 且「重置」清的是空内存写不进宿主目录。
        // 模块进程下显示说明文案,不显示假数据。
        if (activity.packageName != SettingsSyncBridge.MODULE_PACKAGE) {
            parent.addView(TextView(activity).apply {
                text = "统计只记录在宿主进程内,请在播放器「空降助手」面板查看。"
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(0, 0, 0, dp(activity, 8))
            })
            return
        }
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

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun formatCacheTtl(ms: Long): String {
        val minutes = ms / 60_000.0
        return if (minutes % 1.0 == 0.0) {
            "${minutes.toLong()} 分钟"
        } else {
            "${minutes.toString().trimEnd('0').trimEnd('.')} 分钟"
        }
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
     * 数值输入行。
     *
     * [defaultValue] 必须按 key 传真实默认值：缓存 TTL 的真实默认是
     * [SettingsKeys.DEFAULT_CACHE_TTL_MINUTES]（60），硬编码 "0" 会让 UI 显示成 0，
     * 与钩子端实际生效的 60 分钟不一致。非法/空输入也退回该默认值。
     */
    private fun numberRow(
        activity: Activity,
        prefs: SharedPreferences,
        key: String,
        label: String,
        defaultValue: String,
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(activity, 10), 0, dp(activity, 10))
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = label
                textSize = 14f
            })
            addView(EditText(activity).apply {
                setText(prefs.getString(key, defaultValue))
                hint = defaultValue
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val fallback = defaultValue.toFloatOrNull() ?: 0f
                        val value = text.toString().trim().toFloatOrNull()?.coerceAtLeast(0f) ?: fallback
                        // 整数值去掉 ".0":Float.toString() 会把 "60" 回显成 "60.0",与 hint 不一致
                        val normalized = if (value % 1f == 0f) value.toLong().toString() else value.toString()
                        setText(normalized)
                        prefs.edit().putString(key, normalized).apply()
                    }
                }
            })
        }
    }

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
                        if (addr.isEmpty()) {
                            setText(prefs.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER))
                        } else if (!SettingsSanitizer.isValidServerAddress(addr)) {
                            // 非法地址不落盘：SponsorBlockClient 直接拼 `${serverAddress}/api/...`，
                            // 无 scheme / 超长的地址只会让请求全挂，这里回显已存值并提示。
                            Toast.makeText(activity, "服务器地址应以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                            setText(prefs.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER))
                        } else {
                            prefs.edit().putString(SettingsKeys.SERVER_ADDRESS, addr).apply()
                        }
                    }
                }
            })
        }
    }

    private fun defaultSubmitCategoryRow(activity: Activity, prefs: SharedPreferences): View {
        fun currentCategory(): String {
            val saved = prefs.getString(SettingsKeys.DEFAULT_SUBMIT_CATEGORY, SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE)
            return if (saved in SponsorCategories.displayNames) {
                saved ?: SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE
            } else {
                SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE
            }
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
                    setTextColor(primaryTextColor(activity))
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

    /**
     * 用户 ID 行。
     *
     * 构建/refresh **只读** prefs：旧实现会在构建期 `putString(USER_ID, generated)`
     * （构建即写盘、点开设置页就凭空生成一个 ID）。现在没有 ID 时显示占位文案，
     * 生成与落盘只发生在「重置」按钮回调里；提交路径仍有 [UserIdentityStore.getOrCreateUserId] 兜底生成。
     */
    private fun userIdRow(activity: Activity, prefs: SharedPreferences): View {
        fun currentUserId(): String = prefs.getString(SettingsKeys.USER_ID, "").orEmpty()

        val valueView = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(activity, 2), 0, 0)
        }
        fun refresh() {
            valueView.text = currentUserId().takeIf { UserIdentityStore.isValidUserId(it) }
                ?: "（未生成，点\"重置\"生成）"
        }
        refresh()

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 10), 0, dp(activity, 10))
            addView(TextView(activity).apply {
                text = "用户 ID"
                textSize = 16f
                setTextColor(primaryTextColor(activity))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(valueView)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(activity).apply {
                    text = "复制"
                    setOnClickListener {
                        val id = currentUserId()
                        if (!UserIdentityStore.isValidUserId(id)) {
                            Toast.makeText(activity, "尚未生成用户 ID，请先点\"重置\"", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Bili2233 userId", id))
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
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(activity)
            .setTitle("导入用户 ID")
            .setView(edit)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            button.setOnClickListener {
                val userId = edit.text.toString().trim()
                if (!UserIdentityStore.isValidUserId(userId)) {
                    Toast.makeText(activity, "用户 ID 必须是 32 位十六进制", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString(SettingsKeys.USER_ID, userId).apply()
                onSaved()
                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

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

    private fun aboutItem(activity: Activity, title: String, value: String, onClick: (() -> Unit)? = null): View {
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 16f
                setTextColor(primaryTextColor(activity))
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

    private fun hint(activity: Activity, text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.GRAY)
        setPadding(0, 0, 0, dp(activity, 4))
    }

    /**
     * 从主题解析文字色。
     *
     * 宿主内弹窗跑在宿主主题里（可能是深色），硬编码 `#212121` 在深色背景下几乎不可读。
     * 优先取 [android.R.attr.textColorPrimary]，取不到再退回硬编码值。
     * 强调色 `#FF6699` 是品牌色，不走主题解析。
     */
    private fun themedTextColor(activity: Activity, attr: Int, fallbackHex: String): Int {
        val value = TypedValue()
        if (activity.theme.resolveAttribute(attr, value, true)) {
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data
            }
            if (value.resourceId != 0) {
                val resolved = runCatching {
                    activity.resources.getColor(value.resourceId, activity.theme)
                }.getOrNull()
                if (resolved != null) return resolved
            }
        }
        return Color.parseColor(fallbackHex)
    }

    /** 分节标题/正文等主文字色（深色主题下自适应）。 */
    private fun primaryTextColor(activity: Activity): Int =
        themedTextColor(activity, android.R.attr.textColorPrimary, "#212121")

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
