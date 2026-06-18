package com.ctf.bilisb.sponsor

import android.content.Context
import android.util.Log
import com.ctf.bilisb.settings.SettingsKeys
import com.ctf.bilisb.settings.SettingsSyncBridge
import java.util.UUID

class UserIdentityStore(
    private val context: Context,
) {
    fun getOrCreateUserId(): String {
        val prefs = context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val canonical = SettingsSyncBridge.readSnapshot(context)?.userId
        if (isValidUserId(canonical)) {
            val userId = canonical.orEmpty()
            if (prefs.getString(SettingsKeys.USER_ID, null) != userId) {
                prefs.edit().putString(SettingsKeys.USER_ID, userId).apply()
            }
            Log.i(TAG, "Using canonical user ID: ${userId.take(8)}...")
            return userId
        }
        val existing = prefs.getString(SettingsKeys.USER_ID, null)

        if (isValidUserId(existing)) {
            val userId = existing.orEmpty()
            SettingsSyncBridge.writeUserId(context, userId)
            Log.i(TAG, "Using existing user ID: ${userId.take(8)}...")
            return userId
        }

        val legacy = readLegacyUserId()
        if (isValidUserId(legacy)) {
            val userId = legacy.orEmpty()
            prefs.edit().putString(SettingsKeys.USER_ID, userId).apply()
            SettingsSyncBridge.writeUserId(context, userId)
            Log.i(TAG, "Migrated legacy user ID: ${userId.take(8)}...")
            return userId
        }

        val userId = generateUserId()
        val success = prefs.edit().putString(SettingsKeys.USER_ID, userId).commit()
        SettingsSyncBridge.writeUserId(context, userId)
        if (success) Log.i(TAG, "Generated new user ID: ${userId.take(8)}...") else Log.w(TAG, "Failed to save user ID")

        return userId
    }

    private fun readLegacyUserId(): String? =
        context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE).getString(LEGACY_KEY_USER_ID, null)

    companion object {
        private const val TAG = "UserIdentityStore"
        private const val LEGACY_PREFS_NAME = "com.ctf.bilisb.sponsorblock"
        private const val LEGACY_KEY_USER_ID = "user_id"
        private val USER_ID_REGEX = Regex("^[A-Fa-f0-9]{32}$")

        fun generateUserId(): String = UUID.randomUUID().toString().replace("-", "")

        fun isValidUserId(userId: String?): Boolean =
            userId != null && USER_ID_REGEX.matches(userId)
    }
}
