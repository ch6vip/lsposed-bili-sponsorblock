package com.ctf.bilisb.sponsor

import android.content.Context
import java.util.UUID

class UserIdentityStore(
    private val context: Context,
) {
    fun getOrCreateUserId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_USER_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        // APK behavior: generate UUID.randomUUID().toString() and strip hyphens.
        // Persisting it in the target app private prefs is a local implementation
        // choice; the exact storage location was not directly observable.
        val userId = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_USER_ID, userId).apply()
        return userId
    }

    companion object {
        private const val PREFS_NAME = "com.ctf.bilisb.sponsorblock"
        private const val KEY_USER_ID = "user_id"
    }
}
