package com.ctf.bilisb.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
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
import android.widget.Toast

/**
 * 纯代码颜色选择器(不依赖任何三方库)。
 *
 * [colorRow] 生成一行「色块 + 类别名」,点击弹出 [show]:预设色板 + hex 手输。
 * 选定后写回 SharedPreferences 的 color_<category> 键并即时刷新行内色块。
 *
 * 模块设置页与宿主内设置弹窗共用本组件,避免重复。
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
                    // 落盘与预览统一：都用裁掉 alpha 的 #RRGGBB（标记绘制端也只认 RGB）
                    prefs.edit().putString(key, toHex(picked)).apply()
                    applySwatch(picked)
                }
            }
        }
    }

    /**
     * 打开颜色选择器：预览块 + 预设色板 + hex 输入。确定时回调最终颜色（已裁掉 alpha）。
     *
     * 非法输入不再静默回退到上一次选中的颜色：给一次 Toast 提示并保持弹窗打开。
     */
    fun show(activity: Activity, current: Int, onPick: (Int) -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val preview = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        }
        fun applyPreview(color: Int) {
            preview.background = GradientDrawable().apply {
                setColor(toOpaque(color))
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

        // 用 create() + 自定义确认按钮：非法输入时不关闭弹窗（setPositiveButton 会自动 dismiss）
        val dialog = AlertDialog.Builder(activity)
            .setTitle("选择颜色")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val picked = parseHexOrNull(hexInput.text.toString())
                if (picked == null) {
                    Toast.makeText(activity, "颜色格式应为 #RRGGBB", Toast.LENGTH_SHORT).show()
                } else {
                    onPick(picked)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun toHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    /** 裁掉 alpha：落盘与预览都不保留透明度。 */
    private fun toOpaque(color: Int): Int = 0xFF000000.toInt() or (0xFFFFFF and color)

    /**
     * 严格解析 hex 颜色：只接受 `#` 开头的 6/8 位十六进制（8 位会裁掉 alpha）。
     * 非法输入返回 null，由调用方提示，避免静默回退到上一次选中的颜色。
     */
    private fun parseHexOrNull(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (!text.startsWith("#")) return null
        val digits = text.substring(1)
        if (digits.length != 6 && digits.length != 8) return null
        if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return runCatching { toOpaque(Color.parseColor(text)) }.getOrNull()
    }

    private fun parseColorOrDefault(hex: String?, default: String): Int =
        runCatching { toOpaque(Color.parseColor(hex)) }.getOrElse { Color.parseColor(default) }
}
