package com.ctf.bilisb.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.util.Log

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
     *       Uri.parse("content://${SettingsSyncBridge.AUTHORITY}"),
     *       SettingsSyncBridge.METHOD_GET_SETTINGS, null, null
     *   )
     */
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        val ctx = context ?: return null
        if (!isAllowedCaller(ctx)) return null
        val prefs = ctx.getSharedPreferences(SettingsKeys.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        return when (method) {
            SettingsSyncBridge.METHOD_GET_SETTINGS -> SettingsCodec.snapshotToBundle(SettingsCodec.snapshotFromPreferences(prefs))
            SettingsSyncBridge.METHOD_PUT_SETTINGS -> {
                val raw = extras?.getString("settings_json") ?: return null
                val snapshot = runCatching { SettingsCodec.snapshotFromJson(org.json.JSONObject(raw)) }.getOrNull() ?: return null
                SettingsCodec.writeSnapshotToPreferences(prefs, snapshot)
                android.os.Bundle().apply { putBoolean("ok", true) }
            }
            SettingsSyncBridge.METHOD_PUT_USER_ID -> {
                val userId = extras?.getString("user_id")?.trim().orEmpty()
                if (!com.ctf.bilisb.sponsor.UserIdentityStore.isValidUserId(userId)) {
                    return android.os.Bundle().apply { putBoolean("ok", false) }
                }
                prefs.edit().putString(SettingsKeys.USER_ID, userId).apply()
                android.os.Bundle().apply { putBoolean("ok", true) }
            }
            else -> null
        }
    }

    private fun isAllowedCaller(ctx: android.content.Context): Boolean {
        val uidPackages = runCatching {
            ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
        }.getOrNull()
        val allowed = SettingsProviderAccess.isAllowedCaller(
            callingPackage = callingPackage,
            uidPackages = uidPackages,
            selfPackage = ctx.packageName,
        )
        if (!allowed) {
            Log.w(TAG, "Rejected settings provider caller package=$callingPackage uidPackages=${uidPackages?.joinToString()}")
        }
        return allowed
    }

    companion object {
        private const val TAG = "SettingsProvider"
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
