package com.ctf.bilisb.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Bili2233 设置界面。
 *
 * 纯代码构建 UI(不依赖 AndroidX Preference,减小 APK 体积)。
 * 用 MODE_WORLD_READABLE 的 SharedPreferences 存储,供模块 Hook 端读取。
 */
class SettingsActivity : Activity() {

    private lateinit var writer: SettingsWriter
    private val density: Float get() = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    @SuppressLint("WorldReadableFiles")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Bili2233 设置"

        writer = SettingsWriter(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FAFAFA"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        // ===== 总开关 =====
        root.addView(sectionTitle("基本设置"))
        root.addView(switchItem(SettingsKeys.ENABLED, "启用 SponsorBlock", "总开关", true))
        root.addView(switchItem(SettingsKeys.AUTO_SKIP, "自动跳过", "检测到片段时自动跳过", true))

        // ===== 服务器地址 =====
        root.addView(sectionTitle("服务器"))
        root.addView(serverAddressItem())

        // ===== 跳过类别 =====
        root.addView(sectionTitle("跳过类别"))
        root.addView(switchItem(SettingsKeys.CAT_SPONSOR, "赞助广告", "sponsor", true))
        root.addView(switchItem(SettingsKeys.CAT_SELFPROMO, "自我推广", "selfpromo", true))
        root.addView(switchItem(SettingsKeys.CAT_INTERACTION, "三连/互动提醒", "interaction", true))
        root.addView(switchItem(SettingsKeys.CAT_INTRO, "片头/开场", "intro", true))
        root.addView(switchItem(SettingsKeys.CAT_OUTRO, "片尾/结束", "outro", true))
        root.addView(switchItem(SettingsKeys.CAT_PREVIEW, "预览/回顾", "preview", true))
        root.addView(switchItem(SettingsKeys.CAT_MUSIC_OFFTOPIC, "非音乐部分", "music_offtopic", true))
        root.addView(switchItem(SettingsKeys.CAT_FILLER, "填充/玩笑", "filler", true))
        root.addView(switchItem(SettingsKeys.CAT_POI_HIGHLIGHT, "精彩时刻(高亮)", "poi_highlight (只标记不跳过)", true))

        // ===== UI 显示 =====
        root.addView(sectionTitle("界面显示"))
        root.addView(switchItem(SettingsKeys.SHOW_TOAST, "跳过提示", "跳过时显示 Toast", true))
        root.addView(switchItem(SettingsKeys.SHOW_SEEKBAR_MARKER, "进度条标记", "在进度条上标记片段位置", true))
        root.addView(switchItem(SettingsKeys.SHOW_TIME_DEDUCTION, "剩余时长扣减", "时间显示扣除片段后的时长", true))
        root.addView(switchItem(SettingsKeys.SHOW_SUBMIT_BUTTON, "标记按钮", "播放器内显示片段标记按钮", true))

        // ===== 说明 =====
        root.addView(TextView(this).apply {
            text = "提示:修改设置后,重新进入播放页面即可生效，无需重启应用。"
            setTextColor(Color.GRAY)
            textSize = 12f
            setPadding(dp(8), dp(16), dp(8), dp(8))
        })

        setContentView(scroll)
    }

    private fun sectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 14f
        setTextColor(Color.parseColor("#FB7299")) // B站粉
        setPadding(dp(8), dp(20), dp(8), dp(8))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun switchItem(key: String, title: String, summary: String, default: Boolean): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor("#212121"))
        })
        textColumn.addView(TextView(this).apply {
            text = summary
            textSize = 12f
            setTextColor(Color.GRAY)
        })

        val switch = Switch(this).apply {
            isChecked = writer.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                writer.sharedPreferences.edit().putBoolean(key, checked).apply()
            }
        }

        container.addView(textColumn)
        container.addView(switch)
        return container
    }

    private fun serverAddressItem(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        container.addView(TextView(this).apply {
            text = "服务器地址"
            textSize = 16f
            setTextColor(Color.parseColor("#212121"))
        })

        val edit = EditText(this).apply {
            setText(writer.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            textSize = 14f
        }
        container.addView(edit)

        val saveBtn = Button(this).apply {
            text = "保存地址"
            setOnClickListener {
                val addr = edit.text.toString().trim()
                if (addr.isNotEmpty()) {
                    writer.sharedPreferences.edit().putString(SettingsKeys.SERVER_ADDRESS, addr).apply()
                    Toast.makeText(this@SettingsActivity, "已保存: $addr", Toast.LENGTH_SHORT).show()
                }
            }
        }
        container.addView(saveBtn)
        return container
    }
}
