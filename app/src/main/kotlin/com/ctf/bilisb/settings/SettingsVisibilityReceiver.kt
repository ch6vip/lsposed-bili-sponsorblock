package com.ctf.bilisb.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/** Re-establish the targeted visibility grant revoked by package replacement. */
class SettingsVisibilityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) grantHostSettingsVisibility(context)
    }
}

internal fun grantHostSettingsVisibility(context: Context) {
    runCatching {
        context.grantUriPermission(
            SettingsSyncBridge.HOST_PACKAGE,
            Uri.parse("content://${SettingsSyncBridge.AUTHORITY}"),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }.onFailure { Log.w("SettingsProvider", "Cannot grant host settings URI visibility: ${it.message}") }
}
