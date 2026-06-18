package com.ctf.bilisb.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {
    @Test
    fun parsesJsonWithValidationAndDefaults() {
        val json = JSONObject(
            mapOf(
                SettingsKeys.ENABLED to false,
                SettingsKeys.AUTO_SKIP to false,
                SettingsKeys.MANUAL_SKIP to true,
                SettingsKeys.MUTE_SEGMENTS to true,
                SettingsKeys.MIN_SKIP_DURATION to "-2",
                SettingsKeys.SKIP_COUNTDOWN to "3.5",
                SettingsKeys.SERVER_ADDRESS to "https://example.com/",
                SettingsKeys.CACHE_TTL_MINUTES to "-1",
                SettingsKeys.USER_ID to "abc123",
                SettingsKeys.DEFAULT_SUBMIT_CATEGORY to "unknown",
                SettingsKeys.CAT_SPONSOR to true,
                SettingsKeys.CAT_INTRO to false,
                SettingsKeys.SHOW_TOAST to false,
                SettingsKeys.SHOW_SEEKBAR_MARKER to false,
                SettingsKeys.SHOW_TIME_DEDUCTION to false,
                SettingsKeys.SHOW_SUBMIT_BUTTON to false,
                SettingsKeys.colorKey("sponsor") to "#112233",
            )
        )

        val snapshot = SettingsCodec.snapshotFromJson(json)

        assertFalse(snapshot.enabled)
        assertFalse(snapshot.autoSkip)
        assertTrue(snapshot.manualSkip)
        assertTrue(snapshot.muteSegments)
        assertEquals(0f, snapshot.minSkipDurationSec)
        assertEquals(3.5f, snapshot.skipCountdownSec)
        assertEquals("https://example.com/", snapshot.serverAddress)
        assertEquals(0L, snapshot.cacheTtlMs)
        assertEquals("abc123", snapshot.userId)
        assertEquals(SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE, snapshot.defaultSubmitCategory)
        assertTrue("sponsor" in snapshot.enabledCategories)
        assertFalse("intro" in snapshot.enabledCategories)
        assertFalse(snapshot.showToast)
        assertFalse(snapshot.showSeekbarMarker)
        assertFalse(snapshot.showTimeDeduction)
        assertFalse(snapshot.showSubmitButton)
    }

    @Test
    fun jsonRoundTripPreservesCoreFields() {
        val original = SettingsSnapshot.DEFAULT.copy(
            enabled = false,
            minSkipDurationSec = 1.25f,
            skipCountdownSec = 5.0f,
            serverAddress = "https://mirror.example",
            cacheTtlMs = 90L * 60_000L,
            userId = "0123456789abcdef0123456789abcdef",
            defaultSubmitCategory = "intro",
            enabledCategories = setOf("intro", "outro"),
            showTimeDeduction = false,
            categoryColors = SettingsSnapshot.DEFAULT.categoryColors + ("sponsor" to 0xFF123456.toInt()),
        )

        val json = SettingsCodec.snapshotToJson(original)
        val restored = SettingsCodec.snapshotFromJson(SettingsCodec.snapshotToJson(original))

        assertEquals(original.enabled, restored.enabled)
        assertEquals(original.minSkipDurationSec, restored.minSkipDurationSec)
        assertEquals(original.skipCountdownSec, restored.skipCountdownSec)
        assertEquals(original.serverAddress, restored.serverAddress)
        assertEquals(original.cacheTtlMs, restored.cacheTtlMs)
        assertEquals(original.userId, restored.userId)
        assertEquals(original.defaultSubmitCategory, restored.defaultSubmitCategory)
        assertEquals(original.enabledCategories, restored.enabledCategories)
        assertEquals(original.showTimeDeduction, restored.showTimeDeduction)
        assertEquals("#123456", json.getString(SettingsKeys.colorKey("sponsor")))
        assertEquals(0xFF123456.toInt(), restored.categoryColors["sponsor"])
    }

    @Test
    fun roundTripPreservesFractionalCacheTtlMinutes() {
        val original = SettingsSnapshot.DEFAULT.copy(cacheTtlMs = 30_000L)

        val json = SettingsCodec.snapshotToJson(original)
        val fromJson = SettingsCodec.snapshotFromJson(json)

        assertEquals("0.5", json.getString(SettingsKeys.CACHE_TTL_MINUTES))
        assertEquals(30_000L, fromJson.cacheTtlMs)
    }
}
