package com.ctf.bilisb.settings

import android.os.Bundle
import android.preference.PreferenceActivity
import com.ctf.bilisb.R

/**
 * 设置界面 Activity。
 * 用 PreferenceActivity 直接显示设置,不弹对话框。
 */
@Suppress("DEPRECATION")
class LauncherActivity : PreferenceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置 SharedPreferences 名称
        preferenceManager.sharedPreferencesName = SettingsKeys.PREFS_NAME

        // 直接加载设置 XML
        addPreferencesFromResource(R.xml.sponsorblock_settings)
    }
}
