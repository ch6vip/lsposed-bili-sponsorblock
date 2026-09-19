package com.ctf.bilisb.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.ctf.bilisb.sponsor.UserIdentityStore
import org.json.JSONObject

/**
 * ContentProvider IPC 桥接：让 B 站进程能读取模块进程的设置。
 *
 * 模块设置 UI（LauncherActivity / SponsorBlockSettingDialog）运行在模块进程 io.github.ch6vip.bilisb 中，
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
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (!isAllowedCaller(ctx)) return null
        val prefs = ctx.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        return when (method) {
            SettingsSyncBridge.METHOD_GET_SETTINGS -> SettingsCodec.snapshotToBundle(SettingsCodec.snapshotFromPreferences(prefs))
            SettingsSyncBridge.METHOD_PUT_SETTINGS -> {
                val raw = extras?.getString(EXTRA_SETTINGS_JSON) ?: return null
                val json = runCatching { JSONObject(raw) }.getOrElse {
                    Log.w(TAG, "putSettings rejected malformed json: ${it.message}")
                    return null
                }
                val parsed = runCatching { SettingsCodec.snapshotFromJson(json) }.getOrElse {
                    Log.w(TAG, "putSettings rejected malformed json: ${it.message}")
                    return null
                }
                val snapshot = parsed.copy(
                    userId = SettingsSanitizer.sanitizeUserId(
                        json.opt(SettingsKeys.USER_ID) as? String,
                        prefs.getString(SettingsKeys.USER_ID, ""),
                    ),
                    serverAddress = SettingsSanitizer.sanitizeServerAddress(
                        json.opt(SettingsKeys.SERVER_ADDRESS) as? String,
                        prefs.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER),
                    ),
                )
                // 值完全相同则跳过 apply：SharedPreferencesImpl 即使值相同也会回调变更监听器，
                // 模块进程内同步写回会自激循环（writer 又把同样内容推回来），这里在源头剪断。
                val current = SettingsCodec.snapshotFromPreferences(prefs)
                if (current == snapshot) {
                    return Bundle().apply { putBoolean(RESULT_OK, true) }
                }
                SettingsCodec.writeSnapshotToPreferences(prefs, snapshot)
                Bundle().apply { putBoolean(RESULT_OK, true) }
            }
            SettingsSyncBridge.METHOD_PUT_USER_ID -> {
                val userId = extras?.getString(EXTRA_USER_ID)?.trim().orEmpty()
                if (!UserIdentityStore.isValidUserId(userId)) {
                    return Bundle().apply { putBoolean(RESULT_OK, false) }
                }
                prefs.edit().putString(SettingsKeys.USER_ID, userId).apply()
                Bundle().apply { putBoolean(RESULT_OK, true) }
            }
            else -> null
        }
    }

    private fun isAllowedCaller(ctx: Context): Boolean {
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
        private const val EXTRA_SETTINGS_JSON = "settings_json"
        private const val EXTRA_USER_ID = "user_id"
        private const val RESULT_OK = "ok"
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

/**
 * IPC 入参的字段级校验。
 *
 * `putSettings` 是整段 JSON 覆盖，调用方（宿主内弹窗、未来版本）不可信：
 * 这里把 userId / serverAddress 收口到与 `putUserId` / [UserIdentityStore] 相同的规则，
 * 非法值退回「已存值」，已存值也非法再退默认 —— 避免一次脏调用把用户设置打坏。
 *
 * 纯函数（不碰 Context / SharedPreferences），便于 JVM 单测覆盖。
 */
// Note: URI 形态校验（拒 userinfo/空白）下沉到 Codec 读路径 — 见 .agents/notes/implemented/bug-fix/2026-03-21-full-audit-fixes.md
object SettingsSanitizer {
    /** 地址长度上限：防止超长串写进 prefs / JSON 镜像。 */
    private const val MAX_SERVER_ADDRESS_LENGTH = 200

    /** userId 必须是 32 位 hex（与 putUserId 同一套规则）。 */
    fun sanitizeUserId(requested: String?, current: String?): String {
        val candidate = requested?.trim().orEmpty()
        if (UserIdentityStore.isValidUserId(candidate)) return candidate
        return current?.trim().orEmpty()
    }

    /**
     * serverAddress 必须是可解析的 http(s) URI（有 host、无 userinfo、无空白），
     * 否则退回已存值，已存值也非法则退默认。
     */
    fun sanitizeServerAddress(
        requested: String?,
        current: String?,
        default: String = SettingsKeys.DEFAULT_SERVER,
    ): String {
        val candidate = requested?.trim().orEmpty()
        if (isValidServerAddress(candidate)) return candidate
        val stored = current?.trim().orEmpty()
        return if (isValidServerAddress(stored)) stored else default
    }

    /**
     * 校验自定义 SponsorBlock 实例地址。
     *
     * 只做 scheme/host 形态检查：用户本来就可以把实例指到局域网。
     * 拒绝 userinfo（`https://bsbsb.top@evil.com`）、空白/CRLF、空 host、非 http(s)。
     */
    fun isValidServerAddress(address: String): Boolean {
        if (address.length !in 8..MAX_SERVER_ADDRESS_LENGTH) return false
        for (ch in address) {
            if (ch.isWhitespace() || ch == '\u0000') return false
        }
        val uri = runCatching { java.net.URI(address) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (!uri.userInfo.isNullOrEmpty()) return false
        val host = uri.host ?: return false
        return host.isNotBlank()
    }
}

