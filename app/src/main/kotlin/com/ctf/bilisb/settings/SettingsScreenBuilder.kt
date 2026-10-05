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
import android.graphics.drawable.GradientDrawable
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
import com.ctf.bilisb.R
import com.ctf.bilisb.model.SponsorCategories
import com.ctf.bilisb.sponsor.SkipStatsStore
import com.ctf.bilisb.sponsor.UserIdentityStore

object SettingsScreenBuilder {
    private const val REPO_URL = "https://github.com/ch6vip/lsposed-bili-sponsorblock"

    /**
     * 取资源文案。
     *
     * **必须走 [ModuleStrings]**：本文件在宿主进程里被调用时 `activity` 是**宿主的** Activity，
     * 直接 `activity.getString(R.string.x)` 会拿模块的资源 id 去查宿主的资源表 ——
     * 真机上表现为标题显示成 `res/anim/abc_fade_in.xml`（见 ModuleStrings 的注释）。
     * [ModuleStrings] 会按 `packageName` 自动区分「模块自己的进程」与「宿主进程」。
     */
    private fun str(activity: Activity, resId: Int, vararg args: Any): String =
        com.ctf.bilisb.ui.ModuleStrings.get(activity, resId, *args)

    /** statusPanel 的兜底单例(模块进程内复用,避免反复 new SettingsWriter 泄漏线程/监听器)。 */
    @Volatile
    private var sharedStatusWriter: SettingsWriter? = null

    // ---- B 站视觉规范(控制中心专用):品牌粉、白色圆角卡片、浅灰页面底 ----
    private val BILI_PINK = 0xFFFB7299.toInt()
    private val BILI_PINK_LIGHT = 0xFFFF9EB4.toInt()
    private val BILI_TEXT_PRIMARY = 0xFF18191C.toInt()
    private val BILI_TEXT_SECONDARY = 0xFF61666D.toInt()
    private val BILI_CARD_BG = 0xFFFFFFFF.toInt()
    private val BILI_PAGE_BG = 0xFFF1F2F3.toInt()
    private val BILI_DIVIDER = 0xFFE3E5E7.toInt()

    private fun biliCard(activity: Activity, color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(activity, radiusDp).toFloat()
        }

    private fun biliDivider(activity: Activity): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1),
        ).apply { setMargins(dp(activity, 14), 0, dp(activity, 14), 0) }
        setBackgroundColor(BILI_DIVIDER)
    }

    /**
     * 详情弹窗整页(B 站配色):浅灰圆角页面底 + 「‹ 返回」标题栏 + 可滚动内容。
     * 子页不再使用 AlertDialog 原生标题/按钮 —— 透明窗口下它们会露出宿主主题的深色样式。
     */
    fun detailPage(activity: Activity, title: String, onBack: () -> Unit, content: View): View {
        fun header(): View = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 6), dp(activity, 8), dp(activity, 14), dp(activity, 8))
            addView(TextView(activity).apply {
                text = str(activity, R.string.common_back)
                textSize = 15f
                setTextColor(BILI_PINK)
                setTypeface(typeface, Typeface.BOLD)
                isClickable = true
                isFocusable = true
                background = selectableItemBackground(activity)
                setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 12), dp(activity, 4))
                setOnClickListener { onBack() }
            })
            addView(TextView(activity).apply {
                text = title
                textSize = 17f
                setTextColor(BILI_TEXT_PRIMARY)
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = biliCard(activity, BILI_PAGE_BG, 12)
            addView(header())
            addView(wrapScroll(activity, content))
        }
    }

    /** 详情页容器:与控制中心同源的浅灰页面底。 */
    fun biliPage(activity: Activity, block: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = biliCard(activity, BILI_PAGE_BG, 12)
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 4))
            block()
        }

    /** 卡片内的行间分割线(卡片自带水平内边距,不再额外缩进)。 */
    private fun biliDividerInner(activity: Activity): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1),
        )
        setBackgroundColor(BILI_DIVIDER)
    }

    /** 小节:灰色小标题 + 白色圆角卡片(卡片自带水平内边距,行由 [block] 填充)。 */
    private fun biliSection(
        parent: LinearLayout,
        activity: Activity,
        title: String,
        block: LinearLayout.() -> Unit,
    ) {
        parent.addView(TextView(activity).apply {
            text = title
            textSize = 13f
            setTextColor(BILI_TEXT_SECONDARY)
            setPadding(dp(activity, 4), dp(activity, 2), 0, dp(activity, 6))
        })
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14), dp(activity, 6), dp(activity, 14), dp(activity, 6))
            background = biliCard(activity, BILI_CARD_BG, 10)
            block()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(activity, 10) })
    }

    fun buildMain(
        activity: Activity,
        showStatusPanel: Boolean = false,
        statusWriter: SettingsWriter? = null,
        onSponsorBlockClick: () -> Unit,
        onEnhanceClick: (() -> Unit)? = null,
        onUnlockClick: (() -> Unit)? = null,
    ): LinearLayout {
        // 控制中心 = B 站风格:浅灰页面底(#F1F2F3)+ 白色圆角卡片 + 品牌粉头图(#FB7299)。
        // 行内文字用 B 站 App 的固定色板,不跟随宿主主题(与播放器面板的浅色卡片取舍一致)。
        fun card(block: LinearLayout.() -> Unit): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = biliCard(activity, BILI_CARD_BG, 10)
            block()
        }

        fun cardLayoutParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(activity, 10) }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = biliCard(activity, BILI_PAGE_BG, 12)
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 4))

            // 品牌头图(粉渐变)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(BILI_PINK, BILI_PINK_LIGHT)
                    cornerRadius = dp(activity, 12).toFloat()
                }
                setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
                addView(TextView(activity).apply {
                    text = "Bili2233"
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(activity).apply {
                    text = str(activity, R.string.app_tagline)
                    textSize = 12f
                    setTextColor(0xE6FFFFFF.toInt())
                    setPadding(0, dp(activity, 2), 0, 0)
                })
            }, cardLayoutParams())

            if (showStatusPanel) {
                addView(card { addView(statusPanel(activity, statusWriter)) }, cardLayoutParams())
            }

            // 功能卡片:SponsorBlock / B 站增强
            addView(card {
                addView(entryRow(activity, str(activity, R.string.entry_sponsorblock_title), str(activity, R.string.entry_sponsorblock_summary), onSponsorBlockClick))
                if (onEnhanceClick != null) {
                    addView(biliDivider(activity))
                    addView(entryRow(activity, str(activity, R.string.entry_enhance_title), str(activity, R.string.entry_enhance_summary), onEnhanceClick))
                }
                if (onUnlockClick != null) {
                    addView(biliDivider(activity))
                    addView(entryRow(activity, str(activity, R.string.entry_unlock_title), str(activity, R.string.entry_unlock_summary), onUnlockClick))
                }
            }, cardLayoutParams())

            // 关于卡片(版本行可点击 → 跳转 GitHub 项目页;发版说明以 GitHub Releases 为单一来源)
            addView(TextView(activity).apply {
                text = str(activity, R.string.common_about)
                textSize = 13f
                setTextColor(BILI_TEXT_SECONDARY)
                setPadding(dp(activity, 4), dp(activity, 2), 0, dp(activity, 6))
            })
            addView(card {
                addView(aboutItem(activity, str(activity, R.string.common_version), "${BuildConfig.VERSION_NAME}(versionCode ${BuildConfig.VERSION_CODE})") {
                    openUrl(activity, REPO_URL)
                })
                addView(biliDivider(activity))
                addView(aboutItem(activity, str(activity, R.string.common_author), "ch6vip"))
            })
        }
    }

    /**
     * 「B 站增强」详情页(移植自 BiliTamer,MIT 的客户端增强开关)。
     * 与 [buildDetail] 同级:宿主内弹窗由 [com.ctf.bilisb.settings.SponsorBlockSettingDialog]
     * 的 showEnhance 导航,模块设置页由 LauncherActivity 直接 setContentView。
     */
    fun buildEnhance(activity: Activity, prefs: SharedPreferences): LinearLayout {
        return biliPage(activity) {
            biliSection(this, activity, str(activity, R.string.enhance_section)) {
                addView(hint(activity, str(activity, R.string.enhance_note)))
                val rows = listOf(
                    createCheckBox(activity, prefs, SettingsKeys.ENHANCE_IP_LOCATION, str(activity, R.string.enhance_ip_location_title), str(activity, R.string.enhance_ip_location_summary), false),
                    createCheckBox(activity, prefs, SettingsKeys.ENHANCE_HIDE_TRIPLE, str(activity, R.string.enhance_hide_triple_title), str(activity, R.string.enhance_hide_triple_summary), false),
                    createCheckBox(activity, prefs, SettingsKeys.ENHANCE_HIDE_UP_PROMPT, str(activity, R.string.enhance_hide_up_title), str(activity, R.string.enhance_hide_up_summary), false),
                    createCheckBox(activity, prefs, SettingsKeys.ENHANCE_HIDE_VOTE, str(activity, R.string.enhance_hide_vote_title), str(activity, R.string.enhance_hide_vote_summary), false),
                    createCheckBox(activity, prefs, SettingsKeys.ENHANCE_NO_AUTO_REFRESH, str(activity, R.string.enhance_no_refresh_title), str(activity, R.string.enhance_no_refresh_summary), false),
                    createCheckBox(activity, prefs, SettingsKeys.ENHANCE_SHARE_QQ, str(activity, R.string.enhance_share_qq_title), str(activity, R.string.enhance_share_qq_summary), false),
                )
                rows.forEachIndexed { index, row ->
                    if (index > 0) addView(biliDividerInner(activity))
                    addView(row)
                }
            }
        }
    }

    /**
     * 状态面板。优先复用调用方传入的 [statusWriter](LauncherActivity 持有);
     * 不传时用进程级缓存单例 —— 之前这里每次 buildMain 都 new 一个 SettingsWriter,
     * 反复进出主页会持续累积「单线程 IO 线程 + prefs 监听器」。
     */
    /**
     * 解锁番剧设置页（U7）：总开关 + 解析服务器 + 缓存/CDN。
     * 风险提示置顶——账号风控由用户自担是本功能的明示前提（G1 定位反转的一部分）。
     */
    fun buildUnlock(activity: Activity, prefs: SharedPreferences): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            biliSection(this, activity, str(activity, R.string.unlock_section)) {
                addView(hint(activity, str(activity, R.string.unlock_note)))
                addView(
                    createCheckBox(activity, prefs, SettingsKeys.UNLOCK_ENABLED, str(activity, R.string.unlock_enabled_title), str(activity, R.string.unlock_enabled_summary), false),
                )
                addView(textRow(activity, prefs, SettingsKeys.UNLOCK_SERVER_URL, str(activity, R.string.unlock_server_label), str(activity, R.string.unlock_server_hint), InputType.TYPE_TEXT_VARIATION_URI))
                addView(unlockAreaRow(activity, prefs))
                addView(textRow(activity, prefs, SettingsKeys.UNLOCK_SERVER_ACCESS_KEY, str(activity, R.string.unlock_ak_label), "", InputType.TYPE_CLASS_TEXT))
                addView(
                    createCheckBox(activity, prefs, SettingsKeys.UNLOCK_CACHE, str(activity, R.string.unlock_cache_title), str(activity, R.string.unlock_cache_summary), false),
                )
                addView(
                    createCheckBox(activity, prefs, SettingsKeys.UNLOCK_SEARCH, str(activity, R.string.unlock_search_title), str(activity, R.string.unlock_search_summary), false),
                )
                addView(textRow(activity, prefs, SettingsKeys.UNLOCK_UPOS_HOST, str(activity, R.string.unlock_upos_label), str(activity, R.string.unlock_upos_hint), InputType.TYPE_TEXT_VARIATION_URI))
            }
        }
    }

    private fun unlockAreaRow(activity: Activity, prefs: SharedPreferences): View {
        val areas = listOf("cn", "hk", "tw", "th")
        val labels = listOf(
            R.string.unlock_area_cn, R.string.unlock_area_hk,
            R.string.unlock_area_tw, R.string.unlock_area_th,
        ).map { str(activity, it) }
        fun selected(): Int = areas.indexOf(
            SettingsCodec.snapshotFromPreferences(prefs).unlockServerArea.ifBlank { "cn" },
        ).coerceAtLeast(0)
        val row = TextView(activity).apply {
            tag = SettingsKeys.UNLOCK_SERVER_AREA
            textSize = 14f
            setTextColor(primaryTextColor(activity))
            setPadding(0, dp(activity, 14), 0, dp(activity, 14))
            background = selectableItemBackground(activity)
        }
        fun refresh() {
            row.text = str(activity, R.string.unlock_area_value, labels[selected()])
        }
        refresh()
        row.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(str(activity, R.string.unlock_area_label))
                .setSingleChoiceItems(labels.toTypedArray(), selected()) { dialog, which ->
                    prefs.edit().putString(SettingsKeys.UNLOCK_SERVER_AREA, areas[which]).apply()
                    refresh()
                    dialog.dismiss()
                }
                .setNegativeButton(str(activity, R.string.common_cancel), null)
                .show()
        }
        return row
    }

    /** 文本输入行（label + EditText，失焦落盘）。 */
    private fun textRow(
        activity: Activity,
        prefs: SharedPreferences,
        key: String,
        label: String,
        hint: String,
        inputType: Int,
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
                setText(prefs.getString(key, ""))
                this.hint = hint
                this.inputType = inputType
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        prefs.edit().putString(key, text.toString().trim()).apply()
                    }
                }
            })
        }
    }

    private fun statusPanel(activity: Activity, statusWriter: SettingsWriter? = null): View {
        val writer = statusWriter ?: sharedStatusWriter ?: SettingsWriter(activity).also { sharedStatusWriter = it }
        val prefs = writer.sharedPreferences
        val snapshot = SettingsCodec.snapshotFromPreferences(prefs)
        val userIdState = when {
            UserIdentityStore.isValidUserId(snapshot.userId) -> str(activity, R.string.state_user_generated)
            snapshot.userId.isBlank() -> str(activity, R.string.state_user_missing)
            else -> str(activity, R.string.state_user_invalid)
        }
        // "Provider" 是技术名（跨进程 IPC 取了权威存储），不翻译；本地兜底说明来源即可。
        val source = SettingsSyncBridge.readSnapshot(activity)?.let { "Provider" }
            ?: str(activity, R.string.state_source_local_prefs)
        val summary = listOf(
            str(
                activity,
                R.string.state_module,
                if (snapshot.enabled) str(activity, R.string.state_enabled) else str(activity, R.string.state_disabled),
            ),
            str(activity, R.string.state_settings_source, source),
            str(activity, R.string.state_server, snapshot.serverAddress),
            str(activity, R.string.state_cache_ttl, formatCacheTtl(activity, snapshot.cacheTtlMs)),
            str(activity, R.string.state_user_id, userIdState),
            str(
                activity,
                R.string.state_enabled_categories,
                snapshot.enabledCategories.size,
                SettingsKeys.CATEGORY_MAP.size,
            ),
        ).joinToString("\n")

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 12), 0, dp(activity, 12))
            addView(TextView(activity).apply {
                text = str(activity, R.string.common_status)
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
        return biliPage(activity) {
            biliSection(this, activity, str(activity, R.string.settings_section_sponsorblock)) {
                addView(createCheckBox(activity, prefs, SettingsKeys.ENABLED, str(activity, R.string.enable_title), str(activity, R.string.enable_summary), true))
            }

            biliSection(this, activity, str(activity, R.string.section_auto_skip)) {
                val rows = listOf(
                    createCheckBox(activity, prefs, SettingsKeys.AUTO_SKIP, str(activity, R.string.auto_skip_title), str(activity, R.string.auto_skip_summary), true),
                    createCheckBox(activity, prefs, SettingsKeys.MANUAL_SKIP, str(activity, R.string.manual_skip_title), str(activity, R.string.manual_skip_summary), false),
                    createCheckBox(activity, prefs, SettingsKeys.MUTE_SEGMENTS, str(activity, R.string.mute_segments_title), str(activity, R.string.mute_segments_summary), false),
                )
                rows.forEachIndexed { index, row ->
                    if (index > 0) addView(biliDividerInner(activity))
                    addView(row)
                }
                addView(biliDividerInner(activity))
                addView(numberRow(activity, prefs, SettingsKeys.MIN_SKIP_DURATION, str(activity, R.string.min_skip_duration_label), "0"))
                addView(biliDividerInner(activity))
                addView(numberRow(activity, prefs, SettingsKeys.SKIP_COUNTDOWN, str(activity, R.string.skip_countdown_label), "0"))
            }

            biliSection(this, activity, str(activity, R.string.section_categories)) {
                // 顺序、设置键、显示名全部来自 SponsorCategories（单一事实来源）；
                // 这里只保留「一句话说明」这类纯 UI 文案（同样走资源）。
                val categories = SponsorCategories.ordered.map { category ->
                    Triple(category.settingsKey, SponsorCategories.displayName(activity, category.id), categoryHint(activity, category.id))
                }
                categories.forEachIndexed { index, (key, title, summary) ->
                    if (index > 0) addView(biliDividerInner(activity))
                    addView(createCheckBox(activity, prefs, key, title, summary, true))
                }
            }

            biliSection(this, activity, str(activity, R.string.section_marker_colors)) {
                addView(hint(activity, str(activity, R.string.marker_colors_hint)))
                SponsorCategories.ordered.forEachIndexed { index, category ->
                    if (index > 0) addView(biliDividerInner(activity))
                    addView(ColorPickerDialog.colorRow(activity, prefs, category.id, SponsorCategories.displayName(activity, category.id)))
                }
            }

            biliSection(this, activity, str(activity, R.string.section_ui)) {
                val rows = listOf(
                    createCheckBox(activity, prefs, SettingsKeys.SHOW_TOAST, str(activity, R.string.show_toast_title), str(activity, R.string.show_toast_summary), true),
                    createCheckBox(activity, prefs, SettingsKeys.SHOW_SEEKBAR_MARKER, str(activity, R.string.show_marker_title), str(activity, R.string.show_marker_summary), true),
                    createCheckBox(activity, prefs, SettingsKeys.SHOW_TIME_DEDUCTION, str(activity, R.string.show_time_deduction_title), str(activity, R.string.show_time_deduction_summary), true),
                    createCheckBox(activity, prefs, SettingsKeys.SHOW_SKIP_STATS, str(activity, R.string.show_skip_stats_title), str(activity, R.string.show_skip_stats_summary), true),
                )
                rows.forEachIndexed { index, row ->
                    if (index > 0) addView(biliDividerInner(activity))
                    addView(row)
                }
                // 播放器内的「标记按钮」已按需求移除，对应开关不再展示；
                // SettingsKeys.SHOW_SUBMIT_BUTTON 暂时保留以兼容旧配置与编解码。
            }

            biliSection(this, activity, str(activity, R.string.section_stats)) {
                buildStats(activity, this)
            }

            biliSection(this, activity, str(activity, R.string.section_submission)) {
                addView(userIdRow(activity, prefs))
                addView(biliDividerInner(activity))
                addView(defaultSubmitCategoryRow(activity, prefs))
            }

            biliSection(this, activity, str(activity, R.string.section_server)) {
                addView(serverRow(activity, prefs))
                addView(biliDividerInner(activity))
                addView(numberRow(activity, prefs, SettingsKeys.CACHE_TTL_MINUTES, str(activity, R.string.cache_ttl_label), SettingsKeys.DEFAULT_CACHE_TTL_MINUTES))
            }
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
        if (activity.packageName == SettingsSyncBridge.MODULE_PACKAGE) {
            parent.addView(TextView(activity).apply {
                text = str(activity, R.string.stats_host_only)
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
            summary.text = str(
                activity,
                R.string.stats_summary,
                s.totalCount,
                formatDuration(activity, s.totalDurationMs),
            )
            detail.text = if (s.perCategory.isEmpty()) {
                str(activity, R.string.stats_empty)
            } else {
                s.perCategory.entries.joinToString("\n") { (cat, st) ->
                    str(
                        activity,
                        R.string.stats_category_line,
                        SponsorCategories.displayName(activity, cat),
                        st.count,
                        formatDuration(activity, st.durationMs),
                    )
                }
            }
        }
        refresh()

        parent.addView(summary)
        parent.addView(detail)
        parent.addView(Button(activity).apply {
            text = str(activity, R.string.stats_reset)
            setOnClickListener {
                SkipStatsStore.reset()
                refresh()
                Toast.makeText(activity, str(activity, R.string.stats_reset_done), Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun formatDuration(activity: Activity, ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun formatCacheTtl(activity: Activity, ms: Long): String {
        val minutes = ms / 60_000.0
        return if (minutes % 1.0 == 0.0) {
            str(activity, R.string.cache_ttl_minutes, minutes.toLong())
        } else {
            // 小数分钟去掉尾随 0（旧实现 trimEnd('0') 会把 "10" 变成 "1"，属于既有隐患；
            // 这里只在含小数点时裁剪，语义不变但不会误伤整数）
            val text = minutes.toString()
            val trimmed = if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
            str(activity, R.string.cache_ttl_minutes_fractional, trimmed)
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
            setTextColor(BILI_TEXT_PRIMARY)
            // 勾选态用品牌粉,对齐 B 站 App 的开关/勾选视觉
            runCatching {
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(BILI_PINK, 0xFFCCCCCC.toInt()),
                )
            }
            isChecked = prefs.getBoolean(key, defaultValue)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(key, isChecked).apply()
            }
        }
        val summaryView = TextView(activity).apply {
            text = summary
            textSize = 12f
            setTextColor(BILI_TEXT_SECONDARY)
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
                        val max = when (key) {
                            SettingsKeys.MIN_SKIP_DURATION -> SettingsKeys.MAX_MIN_SKIP_DURATION_SECONDS
                            SettingsKeys.SKIP_COUNTDOWN -> SettingsKeys.MAX_SKIP_COUNTDOWN_SECONDS
                            SettingsKeys.CACHE_TTL_MINUTES -> SettingsKeys.MAX_CACHE_TTL_MINUTES.toFloat()
                            else -> Float.MAX_VALUE
                        }
                        val value = text.toString().trim().toFloatOrNull()?.coerceIn(0f, max) ?: fallback
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
                text = str(activity, R.string.server_address_label)
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
                            Toast.makeText(activity, str(activity, R.string.server_address_scheme_error), Toast.LENGTH_SHORT).show()
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
            return if (saved in SponsorCategories.ids) {
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
            valueView.text = SponsorCategories.displayName(activity, currentCategory())
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
                    text = str(activity, R.string.default_category_title)
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
                val categories = SponsorCategories.ordered.map { it.id }
                val labels = categories.map { SponsorCategories.displayName(activity, it) }.toTypedArray()
                val index = categories.indexOf(currentCategory()).coerceAtLeast(0)
                AlertDialog.Builder(activity)
                    .setTitle(str(activity, R.string.default_category_title))
                    .setSingleChoiceItems(labels, index) { dialog, which ->
                        prefs.edit().putString(SettingsKeys.DEFAULT_SUBMIT_CATEGORY, categories[which]).apply()
                        refresh()
                        dialog.dismiss()
                    }
                    .setNegativeButton(str(activity, R.string.common_cancel), null)
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
                ?: str(activity, R.string.user_id_empty_hint)
        }
        refresh()

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 10), 0, dp(activity, 10))
            addView(TextView(activity).apply {
                text = str(activity, R.string.user_id_title)
                textSize = 16f
                setTextColor(primaryTextColor(activity))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(valueView)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(activity).apply {
                    text = str(activity, R.string.common_copy)
                    setOnClickListener {
                        val id = currentUserId()
                        if (!UserIdentityStore.isValidUserId(id)) {
                            Toast.makeText(activity, str(activity, R.string.user_id_not_generated), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Bili2233 userId", id))
                        Toast.makeText(activity, str(activity, R.string.user_id_copied), Toast.LENGTH_SHORT).show()
                    }
                })
                addView(Button(activity).apply {
                    text = str(activity, R.string.common_reset)
                    setOnClickListener {
                        prefs.edit().putString(SettingsKeys.USER_ID, UserIdentityStore.generateUserId()).apply()
                        refresh()
                        Toast.makeText(activity, str(activity, R.string.user_id_reset_done), Toast.LENGTH_SHORT).show()
                    }
                })
                addView(Button(activity).apply {
                    text = str(activity, R.string.common_import)
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
            .setTitle(str(activity, R.string.user_id_import_title))
            .setView(edit)
            .setPositiveButton(str(activity, R.string.common_save), null)
            .setNegativeButton(str(activity, R.string.common_cancel), null)
            .create()
        dialog.setOnShowListener {
            val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            button.setOnClickListener {
                val userId = edit.text.toString().trim()
                if (!UserIdentityStore.isValidUserId(userId)) {
                    Toast.makeText(activity, str(activity, R.string.user_id_invalid_hex), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString(SettingsKeys.USER_ID, userId).apply()
                onSaved()
                Toast.makeText(activity, str(activity, R.string.saved), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun entryRow(activity: Activity, title: String, summary: String, onClick: () -> Unit): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14), dp(activity, 13), dp(activity, 12), dp(activity, 13))
            isClickable = true
            isFocusable = true
            background = selectableItemBackground(activity)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(activity).apply {
                    text = title
                    textSize = 16f
                    setTextColor(BILI_TEXT_PRIMARY)
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(activity).apply {
                    text = summary
                    textSize = 12f
                    setTextColor(BILI_TEXT_SECONDARY)
                    setPadding(0, dp(activity, 2), 0, 0)
                })
            })
            addView(TextView(activity).apply {
                text = "›"
                textSize = 22f
                setTextColor(BILI_PINK)
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
                textSize = 15f
                setTextColor(BILI_TEXT_PRIMARY)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = value
                textSize = 13f
                setTextColor(BILI_TEXT_SECONDARY)
                setPadding(0, dp(activity, 2), 0, 0)
            })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            textCol.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(textCol)
            if (onClick != null) {
                isClickable = true
                background = selectableItemBackground(activity)
                addView(TextView(activity).apply {
                    text = "›"
                    textSize = 22f
                    setTextColor(BILI_PINK)
                })
                setOnClickListener { onClick() }
            }
        }
    }

    private fun openUrl(activity: Activity, url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(activity, str(activity, R.string.cannot_open_link), Toast.LENGTH_SHORT).show()
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
     * 「跳过类别」每一行的一句话说明。
     *
     * 分类与显示名走 [SponsorCategories]（权威表），这段解释也走资源；
     * 新增分类时若忘了补说明，会退回显示名而不是崩掉。
     */
    private fun categoryHint(activity: Activity, category: String): String = when (category) {
        "sponsor" -> str(activity, R.string.category_hint_sponsor)
        "selfpromo" -> str(activity, R.string.category_hint_selfpromo)
        "interaction" -> str(activity, R.string.category_hint_interaction)
        "intro" -> str(activity, R.string.category_hint_intro)
        "outro" -> str(activity, R.string.category_hint_outro)
        "preview" -> str(activity, R.string.category_hint_preview)
        "music_offtopic" -> str(activity, R.string.category_hint_music_offtopic)
        "filler" -> str(activity, R.string.category_hint_filler)
        com.ctf.bilisb.model.SponsorCategories.POI_HIGHLIGHT -> str(activity, R.string.category_hint_poi_highlight)
        else -> SponsorCategories.displayName(activity, category)
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
