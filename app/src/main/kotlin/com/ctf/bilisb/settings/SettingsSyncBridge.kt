package com.ctf.bilisb.settings

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.ctf.bilisb.host.HostTargets

object SettingsSyncBridge {
    const val MODULE_PACKAGE = "io.github.ch6vip.bilisb"

    /** 目标宿主包名（bilibili 6.5.0 国际版）。 */
    const val HOST_PACKAGE = HostTargets.HOST_PACKAGE
    /**
     * Provider authority,由 applicationId 派生(与 AndroidManifest 里的
     * `${applicationId}.settings` 占位符同源)。
     * 之前是字面量,应用 ID 改过一次(com.ctf → io.github.ch6vip)时两处极易漏改一处,
     * 宿主端 IPC 会静默失败 —— 现在代码侧跟 BuildConfig 走,manifest 侧跟占位符走,不可能再漂移。
     */
    val AUTHORITY: String = com.ctf.bilisb.BuildConfig.APPLICATION_ID + ".settings"
    const val METHOD_GET_SETTINGS = "getSettings"
    const val METHOD_PUT_SETTINGS = "putSettings"
    const val METHOD_PUT_USER_ID = "putUserId"
    private const val EXTRA_JSON = "settings_json"
    private const val EXTRA_USER_ID = "user_id"
    private const val TAG = "SettingsSyncBridge"

    fun readSnapshot(context: Context): SettingsSnapshot? {
        return runCatching {
            val bundle = context.contentResolver.call(contentUri(), METHOD_GET_SETTINGS, null, null) ?: return null
            SettingsCodec.snapshotFromBundle(bundle)
        }.getOrElse {
            Log.w(TAG, "readSnapshot failed: ${it.message}")
            null
        }
    }

    fun writeSnapshot(context: Context, snapshot: SettingsSnapshot): Boolean {
        return runCatching {
            val extras = Bundle().apply {
                putString(EXTRA_JSON, SettingsCodec.snapshotToJson(snapshot).toString())
            }
            val result = context.contentResolver.call(contentUri(), METHOD_PUT_SETTINGS, null, extras)
            result?.getBoolean("ok", false) == true
        }.getOrElse {
            Log.w(TAG, "writeSnapshot failed: ${it.message}")
            false
        }
    }

    fun writeUserId(context: Context, userId: String): Boolean {
        return runCatching {
            val extras = Bundle().apply {
                putString(EXTRA_USER_ID, userId)
            }
            val result = context.contentResolver.call(contentUri(), METHOD_PUT_USER_ID, null, extras)
            result?.getBoolean("ok", false) == true
        }.getOrElse {
            Log.w(TAG, "writeUserId failed: ${it.message}")
            false
        }
    }

    private fun contentUri(): Uri = Uri.parse("content://$AUTHORITY")
}
