package com.ctf.bilisb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SheetStateFormatter] 的单测。
 *
 * 这个文件**不碰任何 android.\* 类型**：面板本体（Dialog/View）没有 Robolectric 就跑不了，
 * 所以「面板长什么样」在真机上验收，而「面板上写什么字」在这里覆盖——
 * 文案里的边界（0 片段 / null / 负数 / 超长 ID）恰恰是最容易写错又最容易被忽略的部分。
 * （原文件名 SponsorBlockPlayerSheetTest 名不副实——没有一行测到面板本体,已改名。）
 */
class SheetStateFormatterTest {

    // ------------------------------------------------------------ 片段信息

    @Test
    fun segmentInfoWithoutInsideSegment() {
        assertEquals("1 个片段 · 播放头不在片段内", SheetStateFormatter.formatSegmentInfo(1, null))
        assertEquals("3 个片段 · 播放头不在片段内", SheetStateFormatter.formatSegmentInfo(3, ""))
    }

    @Test
    fun segmentInfoWithInsideSegment() {
        assertEquals(
            "1 个片段 · 播放头在片段内：赞助 300.0-600.0s",
            SheetStateFormatter.formatSegmentInfo(1, "赞助 300.0-600.0s"),
        )
    }

    @Test
    fun segmentInfoTreatsBlankLabelAsOutside() {
        // 调用方可能把「没有片段」表示成空白串而不是 null，两者必须等价
        assertEquals("0 个片段 · 播放头不在片段内", SheetStateFormatter.formatSegmentInfo(0, "   "))
        assertEquals("0 个片段 · 播放头不在片段内", SheetStateFormatter.formatSegmentInfo(0, "\t\n"))
    }

    @Test
    fun segmentInfoClampsNegativeCount() {
        // 网络/缓存脏数据不该显示成 "-2 个片段"
        assertEquals("0 个片段 · 播放头不在片段内", SheetStateFormatter.formatSegmentInfo(-2, null))
    }

    @Test
    fun segmentInfoKeepsLabelWhitespaceTrimmed() {
        assertEquals(
            "2 个片段 · 播放头在片段内：开场动画 0.0-5.0s",
            SheetStateFormatter.formatSegmentInfo(2, "  开场动画 0.0-5.0s  "),
        )
    }

    // ------------------------------------------------------------ 服务信息

    @Test
    fun serviceStatusNormal() {
        assertEquals(
            "状态：正常 · 跳过 140 次 · 节省 5521 秒",
            SheetStateFormatter.formatServiceStatus(ok = true, skippedCount = 140, savedSeconds = 5521L),
        )
    }

    @Test
    fun serviceStatusAbnormal() {
        assertEquals(
            "状态：异常 · 跳过 0 次 · 节省 0 秒",
            SheetStateFormatter.formatServiceStatus(ok = false, skippedCount = 0, savedSeconds = 0L),
        )
    }

    @Test
    fun serviceStatusClampsNegativeNumbers() {
        assertEquals(
            "状态：正常 · 跳过 0 次 · 节省 0 秒",
            SheetStateFormatter.formatServiceStatus(ok = true, skippedCount = -5, savedSeconds = -120L),
        )
    }

    // ------------------------------------------------------------ 手动跳过说明

    @Test
    fun manualSummaryUsesSameSentenceForZero() {
        assertEquals("共 0 个片段 · 点击选择并跳到末尾", SheetStateFormatter.formatManualSummary(0))
        assertEquals("共 1 个片段 · 点击选择并跳到末尾", SheetStateFormatter.formatManualSummary(1))
    }

    @Test
    fun manualSummaryClampsNegativeCount() {
        assertEquals("共 0 个片段 · 点击选择并跳到末尾", SheetStateFormatter.formatManualSummary(-1))
    }

    // ------------------------------------------------------------ 秒数格式化

    @Test
    fun formatSecondsKeepsOneDecimal() {
        assertEquals("0.0s", SheetStateFormatter.formatSeconds(0L))
        assertEquals("12.3s", SheetStateFormatter.formatSeconds(12_300L))
        assertEquals("0.1s", SheetStateFormatter.formatSeconds(100L))
    }

    @Test
    fun formatSecondsTruncatesInsteadOfRounding() {
        // 截断（不是四舍五入）：9.999s 显示 9.9s。若用 String.format("%.1f") 会得到 "10.0s"，
        // 比实际还长，且回填进设置页输入框后会以 10.0 落盘，悄悄放大阈值。
        assertEquals("9.9s", SheetStateFormatter.formatSeconds(9_999L))
        assertEquals("9.9s", SheetStateFormatter.formatSeconds(9_950L))
        assertEquals("0.0s", SheetStateFormatter.formatSeconds(50L))
        assertEquals("0.1s", SheetStateFormatter.formatSeconds(199L))
    }

    @Test
    fun formatSecondsClampsNegative() {
        // seek 未就绪时宿主会给 -1 之类的哨兵值，不能显示成 "-0.1s"
        assertEquals("0.0s", SheetStateFormatter.formatSeconds(-1L))
        assertEquals("0.0s", SheetStateFormatter.formatSeconds(-5_000L))
    }

    @Test
    fun formatSecondsHandlesLongDurations() {
        // 10 分钟的片段：不换算成分钟，保持 "600.0s"，与设置页「最短片段时长」的单位一致
        assertEquals("600.0s", SheetStateFormatter.formatSeconds(600_000L))
    }

    @Test
    fun formatSecondsUsesDotDecimalSeparator() {
        // 强制 Locale.US：否则德/法语区会输出 "12,3s"，与设置页回填的 "12.3" 不一致
        assertEquals("12.3s", SheetStateFormatter.formatSeconds(12_345L))
    }

    @Test
    fun formatSecondsIsIndependentOfDefaultLocale() {
        // 真机上宿主可能把进程默认 Locale 改成德语区，这里显式验证不受影响
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("12.3s", SheetStateFormatter.formatSeconds(12_345L))
            assertEquals("0.0s", SheetStateFormatter.formatSeconds(0L))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    // ------------------------------------------------------------ 用户 ID

    @Test
    fun formatUserIdTruncatesOverTwelveChars() {
        assertEquals("0f8c891f3115…", SheetStateFormatter.formatUserId("0f8c891f3115c0d1e2b3a4f506172839"))
    }

    @Test
    fun formatUserIdKeepsShortIds() {
        assertEquals("", SheetStateFormatter.formatUserId(null))
        assertEquals("", SheetStateFormatter.formatUserId(""))
        assertEquals("abc123", SheetStateFormatter.formatUserId("abc123"))
        // 恰好 12 位：不截断（边界值）
        assertEquals("0123456789ab", SheetStateFormatter.formatUserId("0123456789ab"))
        // 13 位：截到 12 位 + 省略号
        assertEquals("0123456789ab…", SheetStateFormatter.formatUserId("0123456789abc"))
    }

    @Test
    fun formatUserIdTrimsWhitespaceBeforeTruncating() {
        assertEquals("0f8c891f3115…", SheetStateFormatter.formatUserId("  0f8c891f3115c0d1e2  "))
    }

    // ------------------------------------------------------------ 选择列表行

    @Test
    fun manualSegmentItemLabel() {
        assertEquals(
            "赞助/恰饭 300.0s-600.0s (300.0s)",
            SheetStateFormatter.formatManualSegmentItem("赞助/恰饭", 300_000L, 600_000L),
        )
    }

    @Test
    fun manualSegmentItemClampsReversedRange() {
        // 脏数据 end < start：时长显示 0.0s，而不是负数
        assertEquals(
            "开场动画 60.0s-30.0s (0.0s)",
            SheetStateFormatter.formatManualSegmentItem("开场动画", 60_000L, 30_000L),
        )
    }

    @Test
    fun manualSegmentItemFallsBackForBlankCategoryName() {
        assertEquals(
            "片段 0.0s-5.0s (5.0s)",
            SheetStateFormatter.formatManualSegmentItem("   ", 0L, 5_000L),
        )
    }
}
