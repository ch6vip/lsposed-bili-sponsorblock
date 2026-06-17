package com.ctf.bilisb.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * ContentProvider IPC 桥接：让 B 站进程能读取模块进程的设置。
 *
 * 模块设置 UI（LauncherActivity / SponsorBlockSettingDialog）运行在模块进程 com.ctf.bilisb 中，
 * 通过 SharedPreferences 写入设置。Hook 端运行在 tv.danmaku.bili 进程中，
 * 无法直接读取模块的 SharedPreferences 文件（应用沙箱隔离）。
 *
 * 解决方案：声明 exported=true 的 ContentProvider，Hook 端通过 ContentResolver.call()
 * 跨进程读取设置。底层走 Binder IPC，不需要特殊权限。
 */
class SettingsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * 响应跨进程调用，返回全部设置键值对。
     *
     * Hook 端调用方式：
     *   contentResolver.call(
     *       Uri.parse("content://com.ctf.bilisb.settings"),
     *       "getSettings", null, null
     *   )
     */
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        if (method != "getSettings") return null
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(SettingsKeys.PREFS_NAME, android.content.Context.MODE_PRIVATE)

        return android.os.Bundle().apply {
            putBoolean(SettingsKeys.ENABLED, prefs.getBoolean(SettingsKeys.ENABLED, true))
            putBoolean(SettingsKeys.AUTO_SKIP, prefs.getBoolean(SettingsKeys.AUTO_SKIP, true))
            putString(SettingsKeys.SERVER_ADDRESS, prefs.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER))

            SettingsKeys.CATEGORY_MAP.keys.forEach { key ->
                putBoolean(key, prefs.getBoolean(key, true))
            }

            putBoolean(SettingsKeys.SHOW_TOAST, prefs.getBoolean(SettingsKeys.SHOW_TOAST, true))
            putBoolean(SettingsKeys.SHOW_SEEKBAR_MARKER, prefs.getBoolean(SettingsKeys.SHOW_SEEKBAR_MARKER, true))
            putBoolean(SettingsKeys.SHOW_TIME_DEDUCTION, prefs.getBoolean(SettingsKeys.SHOW_TIME_DEDUCTION, true))
            putBoolean(SettingsKeys.SHOW_SUBMIT_BUTTON, prefs.getBoolean(SettingsKeys.SHOW_SUBMIT_BUTTON, true))
        }
    }

    // 以下方法不需要实现，保留默认空实现
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
