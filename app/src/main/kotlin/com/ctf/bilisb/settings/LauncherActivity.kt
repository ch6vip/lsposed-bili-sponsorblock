package com.ctf.bilisb.settings

import android.app.Activity
import android.os.Bundle

/**
 * 模块入口设置页。
 *
 * 和宿主内弹窗共用 [SettingsScreenBuilder]，避免双实现漂移。
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class LauncherActivity : Activity() {
    private lateinit var settingsWriter: SettingsWriter
    private var detailVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsWriter = SettingsWriter(this)
        showMain()
    }

    override fun onBackPressed() {
        if (detailVisible) {
            showMain()
        } else {
            super.onBackPressed()
        }
    }

    private fun showMain() {
        detailVisible = false
        val content = SettingsScreenBuilder.buildMain(this, showStatusPanel = true) {
            showDetail()
        }
        setContentView(SettingsScreenBuilder.wrapScroll(this, content))
    }

    private fun showDetail() {
        detailVisible = true
        val content = SettingsScreenBuilder.buildDetail(this, settingsWriter.sharedPreferences)
        setContentView(SettingsScreenBuilder.wrapScroll(this, content))
    }
}
