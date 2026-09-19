package com.ctf.bilisb.settings

import android.os.Bundle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SettingsCodec 的编解码单测。
 *
 * 类级跑在 JUnit（纯 JVM，Bundle 是 stub）上；Bundle 往返用例用
 * `Assume` + `bundleWorks()` 探测：有 Robolectric 时真跑，没有时 skip。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
        assertEquals("", snapshot.userId)
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

    /** JSON 往返整对象等价（不逐字段断言：任何字段的默认值/映射漂移都会被抓住）。 */
    @Test
    fun jsonRoundTripIsWholeObjectEqual() {
        val original = richSnapshot()

        val restored = SettingsCodec.snapshotFromJson(SettingsCodec.snapshotToJson(original))

        assertEquals(original, restored)
    }

    /**
     * 字段映射层（Bundle/JSON 共用）整对象等价。
     *
     * Bundle 通道自身无法在纯 JVM 单测里跑（见 [bundleRoundTripIsWholeObjectEqual]），
     * 但它只是 Map ↔ Bundle 的薄适配，字段映射的全部逻辑都由这条用例覆盖。
     */
    @Test
    fun mapRoundTripIsWholeObjectEqual() {
        val original = richSnapshot()

        val restored = SettingsCodec.snapshotFromMap(SettingsCodec.snapshotToMap(original))

        assertEquals(original, restored)
    }

    /**
     * `snapshotToBundle → snapshotFromBundle` 整对象等价。
     *
     * Robolectric 提供真 Bundle 实现，这一条会真正执行（不再被 Assume 永久跳过）。
     * 无 Robolectric 的环境（如某些轻量 CI）里 `bundleWorks()` 为 false 时仍会 skip。
     */
    @Test
    fun bundleRoundTripIsWholeObjectEqual() {
        Assume.assumeTrue("android.os.Bundle 在本地单测中被 stub，需要 Robolectric", bundleWorks())

        val original = richSnapshot()

        val restored = SettingsCodec.snapshotFromBundle(SettingsCodec.snapshotToBundle(original))

        assertEquals(original, restored)
    }

    /** 空 JSON `{}` → 全默认（缺失字段逐个走各自默认值，等价于 [SettingsSnapshot.DEFAULT]）。 */
    @Test
    fun emptyJsonObjectYieldsAllDefaults() {
        val snapshot = SettingsCodec.snapshotFromJson(JSONObject())

        assertEquals(SettingsSnapshot.DEFAULT, snapshot)
    }

    /**
     * malformed JSON 字段级容错：坏字段只影响该字段，不抛异常、不影响其它字段。
     */
    @Test
    fun malformedFieldsOnlyAffectTheirOwnField() {
        val json = JSONObject(
            """
            {
              "enabled": 1,
              "server_address": {"nested": true},
              "user_id": 12345678,
              "show_toast": "yes",
              "cache_ttl_minutes": "120",
              "min_skip_duration": "2",
              "cat_intro": false
            }
            """.trimIndent()
        )

        val snapshot = SettingsCodec.snapshotFromJson(json)

        // 坏字段：回退各自默认值，不做隐式强转
        assertTrue(snapshot.enabled)
        assertEquals(SettingsKeys.DEFAULT_SERVER, snapshot.serverAddress)
        assertEquals("", snapshot.userId)
        assertTrue(snapshot.showToast)
        // 好字段：照常解析
        assertEquals(120L * 60_000L, snapshot.cacheTtlMs)
        assertEquals(2f, snapshot.minSkipDurationSec)
        assertFalse("intro" in snapshot.enabledCategories)
    }

    /** 越界数字被 clamp（例如 cache_ttl_minutes 传 1e38）。 */
    @Test
    fun outOfRangeNumbersAreClamped() {
        val json = JSONObject(
            mapOf(
                SettingsKeys.CACHE_TTL_MINUTES to "1e38",
                SettingsKeys.MIN_SKIP_DURATION to "-5",
                SettingsKeys.SKIP_COUNTDOWN to "1e30",
            )
        )

        val snapshot = SettingsCodec.snapshotFromJson(json)

        assertEquals(SettingsKeys.MAX_CACHE_TTL_MINUTES * 60_000L, snapshot.cacheTtlMs)
        assertEquals(0f, snapshot.minSkipDurationSec)
        assertEquals(SettingsKeys.MAX_SKIP_COUNTDOWN_SECONDS, snapshot.skipCountdownSec)
    }

    private fun richSnapshot(): SettingsSnapshot = SettingsSnapshot(
        enabled = false,
        autoSkip = false,
        manualSkip = true,
        muteSegments = true,
        minSkipDurationSec = 12.5f,
        skipCountdownSec = 4f,
        serverAddress = "https://mirror.example:8443/base",
        cacheTtlMs = 90L * 60_000L,
        userId = "0123456789abcdef0123456789abcdef",
        defaultSubmitCategory = "intro",
        enabledCategories = setOf("intro", "outro"),
        showToast = false,
        showSeekbarMarker = false,
        showTimeDeduction = false,
        showSkipStats = false,
        showSubmitButton = false,
        // 颜色必须是 opaque：#RRGGBB 是存储契约，alpha 会被 codec 裁掉
        categoryColors = SettingsKeys.CATEGORY_COLOR_DEFAULTS.mapValues { (category, _) ->
            if (category == "sponsor") 0xFF123456.toInt() else 0xFF654321.toInt()
        },
        ipLocation = true,
        shareQq = true,
    )

    /** Bundle 在纯 JVM 单测里是否真的可用（Robolectric / 完整 android.jar 时才为 true）。 */
    private fun bundleWorks(): Boolean = runCatching {
        val bundle = Bundle().apply { putString("probe", "x") }
        bundle.getString("probe") == "x"
    }.getOrDefault(false)
}
