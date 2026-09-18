package com.ctf.bilisb.sponsor

import android.content.Context
import android.util.Log
import com.ctf.bilisb.settings.SettingsKeys
import com.ctf.bilisb.settings.SettingsSyncBridge
import java.util.UUID

/**
 * SponsorBlock 用户 ID(userID)读取/生成。
 *
 * 这个类在**点击提交按钮的主线程**上被调用,而 [getOrCreateUserId] 会做跨进程
 * Binder IPC(`SettingsSyncBridge.readSnapshot`)+ SharedPreferences 读写。
 * 因此这里做了三层缓存/降级:
 *   1. 进程内 [cachedUserId]:首次解析成功后直接返回,后续调用不再碰 IPC / 磁盘;
 *   2. 优先采纳设置快照里的 canonical userId(外部 UI 可能已经改过);
 *   3. 写盘失败(权限/磁盘满)也固定返回本次生成的值 —— 否则下次调用会再生成一个,
 *      同一台设备上会出现多个 userID,统计与提交记录对不上。
 */
class UserIdentityStore(
    private val context: Context,
) {
    @Volatile
    private var cachedUserId: String? = null

    /**
     * 取当前 userID,没有就生成并尽力持久化。
     *
     * 用**类级**锁而不是实例级 `@Synchronized`:controller 每次缓存 miss 都会新建实例,
     * 实例锁拦不住「两个实例并发首次调用」—— 会生成两个不同 ID 并各自写盘。
     * 命中缓存后开销只是一次锁竞争(纳秒级)。
     */
    fun getOrCreateUserId(): String = synchronized(UserIdentityStore::class.java) {
        cachedUserId?.let { return it }

        val prefs = context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val canonical = SettingsSyncBridge.readSnapshot(context)?.userId
        if (isValidUserId(canonical)) {
            val userId = canonical.orEmpty()
            if (prefs.getString(SettingsKeys.USER_ID, null) != userId) {
                prefs.edit().putString(SettingsKeys.USER_ID, userId).apply()
            }
            Log.i(TAG, "Using canonical user ID: ${userId.take(8)}...")
            return cache(userId)
        }
        val existing = prefs.getString(SettingsKeys.USER_ID, null)

        if (isValidUserId(existing)) {
            val userId = existing.orEmpty()
            SettingsSyncBridge.writeUserId(context, userId)
            Log.i(TAG, "Using existing user ID: ${userId.take(8)}...")
            return cache(userId)
        }

        val legacy = readLegacyUserId()
        if (isValidUserId(legacy)) {
            val userId = legacy.orEmpty()
            prefs.edit().putString(SettingsKeys.USER_ID, userId).apply()
            SettingsSyncBridge.writeUserId(context, userId)
            Log.i(TAG, "Migrated legacy user ID: ${userId.take(8)}...")
            return cache(userId)
        }

        val userId = generateUserId()
        // apply():主线程不再等磁盘。写失败也无所谓 —— 值已经进 [cachedUserId],
        // 本次进程生命周期内始终返回同一个 ID。
        prefs.edit().putString(SettingsKeys.USER_ID, userId).apply()
        SettingsSyncBridge.writeUserId(context, userId)
        Log.i(TAG, "Generated new user ID: ${userId.take(8)}...")

        return cache(userId)
    }

    private fun cache(userId: String): String {
        cachedUserId = userId
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
