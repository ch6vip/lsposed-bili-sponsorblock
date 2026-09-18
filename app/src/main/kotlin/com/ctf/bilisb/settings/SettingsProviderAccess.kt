package com.ctf.bilisb.settings

object SettingsProviderAccess {
    /**
     * 调用方准入:只放行「自身 App」和「目标宿主」。
     *
     * 三个条件都要满足:
     *   1. callingPackage(非 null 时)必须在允许集合内;
     *   2. uid 解析出的包名里至少有一个在允许集合内;
     *   3. callingPackage 必须属于该 uid 解析出的包名 —— 否则调用方是在谎报包名
     *      (uid 属于宿主、包名却是别的 App),一律拒绝。
     */
    fun isAllowedCaller(
        callingPackage: String?,
        uidPackages: Array<String>?,
        selfPackage: String,
    ): Boolean {
        val allowedPackages = setOf(selfPackage, SettingsSyncBridge.HOST_PACKAGE)
        if (callingPackage != null && callingPackage !in allowedPackages) {
            return false
        }
        val packages = uidPackages ?: return false
        if (packages.none { it in allowedPackages }) {
            return false
        }
        if (callingPackage != null && callingPackage !in packages) {
            return false
        }
        return true
    }
}
