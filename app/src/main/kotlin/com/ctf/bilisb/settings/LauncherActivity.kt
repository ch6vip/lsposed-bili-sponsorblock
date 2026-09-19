package com.ctf.bilisb.settings

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup

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
        clearFocusRecursively(window?.decorView)
        if (detailVisible) {
            showMain()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        // 返回键/切后台不触发 EditText 失焦(numberRow 只在失焦时落盘):
        // 用户改完「缓存 TTL / 最小时长 / 倒计时」直接按返回键,改动会静默丢失。
        // 这里在离开前主动清掉焦点,触发同一套落盘路径。
        clearFocusRecursively(window?.decorView)
    }

    override fun onDestroy() {
        // 每个 Activity 实例一个 writer;不 close 的话旋转/深色切换每次泄漏
        // 一条守护 IO 线程 + 一个 prefs 监听器,还会让每次改动重复做镜像写与 provider 推送。
        if (::settingsWriter.isInitialized) {
            settingsWriter.close()
        }
        super.onDestroy()
    }

    private fun clearFocusRecursively(view: View?) {
        view ?: return
        if (view.hasFocus()) {
            view.clearFocus()
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearFocusRecursively(view.getChildAt(i))
            }
        }
    }

    private fun showMain() {
        detailVisible = false
        val content = SettingsScreenBuilder.buildMain(
            this,
            showStatusPanel = true,
            statusWriter = settingsWriter,
            onSponsorBlockClick = { showDetail() },
            onEnhanceClick = { showEnhance() },
        )
        setContentView(SettingsScreenBuilder.wrapScroll(this, content))
    }

    private fun showEnhance() {
        detailVisible = true
        val content = SettingsScreenBuilder.buildEnhance(this, settingsWriter.sharedPreferences)
        setContentView(SettingsScreenBuilder.wrapScroll(this, content))
    }

    private fun showDetail() {
        detailVisible = true
        val content = SettingsScreenBuilder.buildDetail(this, settingsWriter.sharedPreferences)
        setContentView(SettingsScreenBuilder.wrapScroll(this, content))
    }
}
