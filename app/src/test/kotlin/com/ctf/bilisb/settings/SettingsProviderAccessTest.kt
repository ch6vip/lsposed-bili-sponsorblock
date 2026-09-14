package com.ctf.bilisb.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsProviderAccessTest {
    @Test
    fun allowsModuleAndHostPackagesResolvedFromUid() {
        assertTrue(
            SettingsProviderAccess.isAllowedCaller(
                callingPackage = SettingsSyncBridge.HOST_PACKAGE,
                uidPackages = arrayOf(SettingsSyncBridge.HOST_PACKAGE),
                selfPackage = SettingsSyncBridge.MODULE_PACKAGE,
            )
        )

        assertTrue(
            SettingsProviderAccess.isAllowedCaller(
                callingPackage = SettingsSyncBridge.MODULE_PACKAGE,
                uidPackages = arrayOf(SettingsSyncBridge.MODULE_PACKAGE),
                selfPackage = SettingsSyncBridge.MODULE_PACKAGE,
            )
        )
    }

    @Test
    fun rejectsNullCallerUnlessUidResolvesToAllowedPackage() {
        assertFalse(
            SettingsProviderAccess.isAllowedCaller(
                callingPackage = null,
                uidPackages = null,
                selfPackage = SettingsSyncBridge.MODULE_PACKAGE,
            )
        )

        assertTrue(
            SettingsProviderAccess.isAllowedCaller(
                callingPackage = null,
                uidPackages = arrayOf(SettingsSyncBridge.HOST_PACKAGE),
                selfPackage = SettingsSyncBridge.MODULE_PACKAGE,
            )
        )
    }

    @Test
    fun rejectsMismatchedCallingPackageEvenWhenUidHasAllowedPackage() {
        assertFalse(
            SettingsProviderAccess.isAllowedCaller(
                callingPackage = "com.example.attacker",
                uidPackages = arrayOf(SettingsSyncBridge.HOST_PACKAGE),
                selfPackage = SettingsSyncBridge.MODULE_PACKAGE,
            )
        )
    }

    @Test
    fun rejectsUnknownUidPackages() {
        assertFalse(
            SettingsProviderAccess.isAllowedCaller(
                callingPackage = SettingsSyncBridge.HOST_PACKAGE,
                uidPackages = arrayOf("com.example.other"),
                selfPackage = SettingsSyncBridge.MODULE_PACKAGE,
            )
        )
    }

    /**
     * callingPackage 与 uidPackages 不一致（包名谎报）必须拒绝：
     * 即使两者都各自落在允许集合里，uid 解析出来的包名里也必须包含 callingPackage。
     */
    @Test
    fun rejectsCallingPackageThatDoesNotBelongToCallingUid() {
        assertFalse(
            SettingsProviderAccess.isAllowedCaller(
                callingPackage = SettingsSyncBridge.MODULE_PACKAGE,
                uidPackages = arrayOf(SettingsSyncBridge.HOST_PACKAGE),
                selfPackage = SettingsSyncBridge.MODULE_PACKAGE,
            )
        )

        assertFalse(
            SettingsProviderAccess.isAllowedCaller(
                callingPackage = SettingsSyncBridge.HOST_PACKAGE,
                uidPackages = arrayOf(SettingsSyncBridge.MODULE_PACKAGE),
                selfPackage = SettingsSyncBridge.MODULE_PACKAGE,
            )
        )

        // uid 有多个包时，只要 callingPackage 在其中就算一致
        assertTrue(
            SettingsProviderAccess.isAllowedCaller(
                callingPackage = SettingsSyncBridge.HOST_PACKAGE,
                uidPackages = arrayOf(SettingsSyncBridge.HOST_PACKAGE, "com.example.shared"),
                selfPackage = SettingsSyncBridge.MODULE_PACKAGE,
            )
        )
    }
}
