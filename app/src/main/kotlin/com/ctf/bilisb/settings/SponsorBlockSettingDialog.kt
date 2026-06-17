package com.ctf.bilisb.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

/**
 * Bili2233 设置对话框。
 *
 * 纯代码构建设置界面，不依赖XML和PreferenceFragment。
 */
object SponsorBlockSettingDialog {
    fun show(activity: Activity, onDismiss: (() -> Unit)? = null) {
        val writer = SettingsWriter(activity)
        val prefs = writer.sharedPreferences

        // 创建滚动容器
        val scrollView = ScrollView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 主容器
        val mainLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        // 添加所有设置项
        addBasicSettings(activity, mainLayout, prefs)
        addSkipStrategySettings(activity, mainLayout, prefs)
        addCategorySettings(activity, mainLayout, prefs)
        addUISettings(activity, mainLayout, prefs)
        addServerSettings(activity, mainLayout, prefs)

        scrollView.addView(mainLayout)

        // 显示对话框
        AlertDialog.Builder(activity)
            .setTitle("Bili2233 设置")
            .setView(scrollView)
            .setPositiveButton("确定") { _, _ ->
                // 设置已自动保存
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { onDismiss?.invoke() }
            .show()
    }

    private fun addBasicSettings(activity: Activity, parent: LinearLayout, prefs: SharedPreferences) {
        parent.addView(createSectionTitle(activity, "基本设置"))

        parent.addView(createCheckBox(
            activity, prefs,
            SettingsKeys.ENABLED,
            "启用 SponsorBlock",
            "关闭后模块不工作",
            true
        ))

        parent.addView(createCheckBox(
            activity, prefs,
            SettingsKeys.AUTO_SKIP,
            "自动跳过",
            "自动跳过检测到的片段",
            true
        ))
    }

    private fun addSkipStrategySettings(activity: Activity, parent: LinearLayout, prefs: SharedPreferences) {
        parent.addView(createSectionTitle(activity, "跳过策略"))

        parent.addView(createCheckBox(
            activity, prefs,
            SettingsKeys.MANUAL_SKIP,
            "手动跳过",
            "片段内显示跳过按钮,点按才跳(覆盖自动跳过)",
            false
        ))

        parent.addView(createCheckBox(
            activity, prefs,
            SettingsKeys.MUTE_SEGMENTS,
            "片段静音",
            "对 mute 类片段静音而非跳过",
            false
        ))

        parent.addView(numberRow(activity, prefs, SettingsKeys.MIN_SKIP_DURATION, "最小片段时长(秒)："))
        parent.addView(numberRow(activity, prefs, SettingsKeys.SKIP_COUNTDOWN, "自动跳过倒计时(秒)："))
    }

    /** 数字(秒)输入行:失焦时规整为非负数并存回。 */
    private fun numberRow(
        activity: Activity,
        prefs: SharedPreferences,
        key: String,
        label: String,
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 10)
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

    private fun addCategorySettings(activity: Activity, parent: LinearLayout, prefs: SharedPreferences) {
        parent.addView(createSectionTitle(activity, "跳过类别"))

        val categories = listOf(
            Triple(SettingsKeys.CAT_SPONSOR, "赞助/恰饭", "付费推广、赞助商"),
            Triple(SettingsKeys.CAT_SELFPROMO, "自我推广", "自己的商品、链接"),
            Triple(SettingsKeys.CAT_INTERACTION, "互动提醒", "点赞、关注提示"),
            Triple(SettingsKeys.CAT_INTRO, "开场动画", "片头动画"),
            Triple(SettingsKeys.CAT_OUTRO, "结束画面", "片尾鸣谢"),
            Triple(SettingsKeys.CAT_PREVIEW, "回顾/概要", "前情回顾"),
            Triple(SettingsKeys.CAT_MUSIC_OFFTOPIC, "非音乐片段", "MV中非音乐部分"),
            Triple(SettingsKeys.CAT_FILLER, "填充内容", "笑话、重复片段"),
            Triple(SettingsKeys.CAT_POI_HIGHLIGHT, "精彩时刻", "视频精彩部分")
        )

        for ((key, title, summary) in categories) {
            parent.addView(createCheckBox(activity, prefs, key, title, summary, true))
        }
    }

    private fun addUISettings(activity: Activity, parent: LinearLayout, prefs: SharedPreferences) {
        parent.addView(createSectionTitle(activity, "界面显示"))

        parent.addView(createCheckBox(
            activity, prefs,
            SettingsKeys.SHOW_TOAST,
            "跳过提示",
            "跳过时显示Toast",
            true
        ))

        parent.addView(createCheckBox(
            activity, prefs,
            SettingsKeys.SHOW_SEEKBAR_MARKER,
            "进度条标记",
            "标记片段位置",
            true
        ))

        parent.addView(createCheckBox(
            activity, prefs,
            SettingsKeys.SHOW_TIME_DEDUCTION,
            "时间扣减",
            "总时长减去跳过时长",
            true
        ))

        parent.addView(createCheckBox(
            activity, prefs,
            SettingsKeys.SHOW_SUBMIT_BUTTON,
            "标记按钮",
            "显示提交按钮",
            true
        ))
    }

    private fun addServerSettings(activity: Activity, parent: LinearLayout, prefs: SharedPreferences) {
        parent.addView(createSectionTitle(activity, "服务器"))

        val serverLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 10)
        }

        val label = TextView(activity).apply {
            text = "服务器地址："
            textSize = 14f
        }

        val editText = EditText(activity).apply {
            setText(prefs.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER))
            hint = SettingsKeys.DEFAULT_SERVER
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    prefs.edit().putString(SettingsKeys.SERVER_ADDRESS, text.toString()).apply()
                }
            }
        }

        serverLayout.addView(label)
        serverLayout.addView(editText)
        parent.addView(serverLayout)
    }

    private fun createSectionTitle(activity: Activity, title: String): TextView {
        return TextView(activity).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor("#FF6699"))
            setPadding(0, 30, 0, 10)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    private fun createCheckBox(
        activity: Activity,
        prefs: SharedPreferences,
        key: String,
        title: String,
        summary: String,
        defaultValue: Boolean
    ): LinearLayout {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
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
            setPadding(checkBox.paddingLeft + 50, 0, 0, 0)
        }

        layout.addView(checkBox)
        layout.addView(summaryView)
        return layout
    }
}
