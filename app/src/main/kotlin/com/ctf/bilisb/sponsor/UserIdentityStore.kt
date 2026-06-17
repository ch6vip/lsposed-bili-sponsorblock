package com.ctf.bilisb.sponsor

import android.content.Context
import android.util.Log
import java.util.UUID

class UserIdentityStore(
    private val context: Context,
) {
    fun getOrCreateUserId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_USER_ID, null)

        if (!existing.isNullOrBlank()) {
            Log.i(TAG, "Using existing user ID: ${existing.take(8)}...")
            return existing
        }

        // Generate UUID and strip hyphens (SponsorBlock standard format)
        val userId = UUID.randomUUID().toString().replace("-", "")

        // 保存到SharedPreferences
        val success = prefs.edit().putString(KEY_USER_ID, userId).commit()
        if (success) {
            Log.i(TAG, "Generated new user ID: ${userId.take(8)}...")
        } else {
            Log.w(TAG, "Failed to save user ID to SharedPreferences")
        }

        return userId
    }

    companion object {
        private const val TAG = "UserIdentityStore"
        private const val PREFS_NAME = "com.ctf.bilisb.sponsorblock"
        private const val KEY_USER_ID = "user_id"
    }
}
