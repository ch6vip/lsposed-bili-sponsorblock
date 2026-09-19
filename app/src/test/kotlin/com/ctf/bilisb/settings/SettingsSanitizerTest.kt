package com.ctf.bilisb.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `putSettings` 的字段级校验（纯函数，不需要 Context）。
 */
class SettingsSanitizerTest {
    private val validUserId = "0123456789abcdef0123456789abcdef"
    private val currentUserId = "fedcba9876543210fedcba9876543210"

    @Test
    fun keepsValidUserId() {
        assertEquals(validUserId, SettingsSanitizer.sanitizeUserId(validUserId, currentUserId))
        // 大写 hex 同样合法（与 UserIdentityStore.isValidUserId 同规则）
        val upperCaseId = "ABCDEF0123456789ABCDEF0123456789"
        assertEquals(upperCaseId, SettingsSanitizer.sanitizeUserId(upperCaseId, currentUserId))
        // 前后空白会被裁掉
        assertEquals(validUserId, SettingsSanitizer.sanitizeUserId("  $validUserId  ", currentUserId))
    }

    @Test
    fun invalidUserIdFallsBackToStoredValue() {
        // 长度不对 / 非 hex / null / 空：一律保留已存值，不写脏数据
        assertEquals(currentUserId, SettingsSanitizer.sanitizeUserId("abc123", currentUserId))
        assertEquals(currentUserId, SettingsSanitizer.sanitizeUserId("0123456789abcdef0123456789abcde", currentUserId))
        assertEquals(currentUserId, SettingsSanitizer.sanitizeUserId("zzzz56789abcdef0123456789abcdef0", currentUserId))
        assertEquals(currentUserId, SettingsSanitizer.sanitizeUserId(null, currentUserId))
        assertEquals(currentUserId, SettingsSanitizer.sanitizeUserId("", currentUserId))
        // 已存值也没有时退空串
        assertEquals("", SettingsSanitizer.sanitizeUserId("abc123", ""))
    }

    @Test
    fun keepsHttpAndHttpsServerAddress() {
        assertEquals(
            "https://bsbsb.top",
            SettingsSanitizer.sanitizeServerAddress("https://bsbsb.top", null),
        )
        assertEquals(
            "http://10.0.2.2:8080/",
            SettingsSanitizer.sanitizeServerAddress("  http://10.0.2.2:8080/  ", null),
        )
    }

    @Test
    fun rejectsUserInfoEmptyHostAndWhitespace() {
        assertFalse(SettingsSanitizer.isValidServerAddress("https://bsbsb.top@evil.com"))
        assertFalse(SettingsSanitizer.isValidServerAddress("https://"))
        assertFalse(SettingsSanitizer.isValidServerAddress("https://evil.com\nhost"))
        assertFalse(SettingsSanitizer.isValidServerAddress("http://"))
        assertTrue(SettingsSanitizer.isValidServerAddress("http://10.0.2.2:8080/"))
    }

    @Test
    fun rejectsNonHttpOrTooLongServerAddress() {
        assertFalse(SettingsSanitizer.isValidServerAddress("bsbsb.top"))
        assertFalse(SettingsSanitizer.isValidServerAddress("ftp://bsbsb.top"))
        assertFalse(SettingsSanitizer.isValidServerAddress("javascript:alert(1)"))
        assertFalse(SettingsSanitizer.isValidServerAddress(""))
        assertFalse(SettingsSanitizer.isValidServerAddress("https://" + "a".repeat(200)))
        val stored = "https://bsbsb.top"
        assertEquals(stored, SettingsSanitizer.sanitizeServerAddress("bsbsb.top", stored))
        assertEquals(stored, SettingsSanitizer.sanitizeServerAddress("file:///etc/passwd", stored))
        assertEquals(stored, SettingsSanitizer.sanitizeServerAddress(null, stored))
    }

    @Test
    fun fallsBackToDefaultWhenStoredAddressIsAlsoInvalid() {
        assertEquals(
            SettingsKeys.DEFAULT_SERVER,
            SettingsSanitizer.sanitizeServerAddress("nonsense", "also-nonsense"),
        )
        assertEquals(
            SettingsKeys.DEFAULT_SERVER,
            SettingsSanitizer.sanitizeServerAddress(null, null),
        )
    }

    @Test
    fun acceptsAddressAtLengthLimit() {
        val atLimit = "https://" + "a".repeat(200 - "https://".length)
        assertEquals(200, atLimit.length)
        assertTrue(SettingsSanitizer.isValidServerAddress(atLimit))
    }
}
