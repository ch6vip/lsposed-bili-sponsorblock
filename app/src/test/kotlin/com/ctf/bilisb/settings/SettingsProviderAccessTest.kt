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
}
