package com.ctf.bilisb.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 纯代码颜色选择器(不依赖任何三方库)。
 *
 * [colorRow] 生成一行「色块 + 类别名」,点击弹出 [show]:预设色板 + hex 手输。
 * 选定后写回 SharedPreferences 的 color_<category> 键并即时刷新行内色块。
 *
 * 两个设置界面(SettingsActivity / SponsorBlockSettingDialog)共用本组件,避免重复。
 */
object ColorPickerDialog {
    // 预设色板:含 9 个分类默认色 + 常用基础色,4 列排布。
    private val PRESETS = listOf(
        "#00D200", "#FFFF00", "#AA00FF", "#00FFFF",
        "#0064FF", "#FF8000", "#FF00B4", "#7F00FF",
        "#FF1E1E", "#FF0000", "#00FF00", "#0000FF",
        "#FFC400", "#FFFFFF", "#808080", "#000000",
    )

    /** 生成一行颜色配置项。点击整行打开选择器。 */
    fun colorRow(
        activity: Activity,
        prefs: SharedPreferences,
        category: String,
        displayName: String,
    ): View {
        val key = SettingsKeys.colorKey(category)
        val default = SettingsKeys.CATEGORY_COLOR_DEFAULTS[category] ?: "#FFC400"
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val swatch = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        }
        fun applySwatch(color: Int) {
            swatch.background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.parseColor("#40000000"))
            }
        }
        applySwatch(parseColorOrDefault(prefs.getString(key, default), default))

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(swatch)
            addView(TextView(activity).apply {
                text = displayName
                textSize = 15f
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            setOnClickListener {
                val current = parseColorOrDefault(prefs.getString(key, default), default)
                show(activity, current) { picked ->
                    prefs.edit().putString(key, toHex(picked)).apply()
                    applySwatch(picked)
                }
            }
        }
    }

    /** 打开颜色选择器:预览块 + 预设色板 + hex 输入。确定时回调最终颜色。 */
    fun show(activity: Activity, current: Int, onPick: (Int) -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var selected = current

        val preview = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        }
        fun applyPreview(color: Int) {
            preview.background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#40000000"))
            }
        }
        applyPreview(current)

        val hexInput = EditText(activity).apply {
            setText(toHex(current))
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
            hint = "#RRGGBB"
        }

        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        PRESETS.chunked(4).forEach { rowColors ->
            grid.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowColors.forEach { hex ->
                    val color = Color.parseColor(hex)
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        }
                        background = GradientDrawable().apply {
                            setColor(color)
                            cornerRadius = dp(8).toFloat()
                            setStroke(dp(1), Color.parseColor("#40000000"))
                        }
                        setOnClickListener {
                            selected = color
                            applyPreview(color)
                            hexInput.setText(toHex(color))
                        }
                    })
                }
            })
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(preview)
            addView(grid)
            addView(TextView(activity).apply {
                text = "自定义 (hex)："
                textSize = 13f
            })
            addView(hexInput)
        }

        AlertDialog.Builder(activity)
            .setTitle("选择颜色")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("确定") { _, _ ->
                val fromHex = runCatching { Color.parseColor(hexInput.text.toString().trim()) }.getOrNull()
                onPick(fromHex ?: selected)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    private fun parseColorOrDefault(hex: String?, default: String): Int =
        runCatching { Color.parseColor(hex) }.getOrElse { Color.parseColor(default) }
}
