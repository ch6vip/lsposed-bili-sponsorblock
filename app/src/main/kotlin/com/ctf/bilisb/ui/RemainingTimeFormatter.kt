package com.ctf.bilisb.ui

import android.text.SpannableStringBuilder
import com.ctf.bilisb.model.SponsorSegment
import java.util.Locale

object RemainingTimeFormatter {
    // 对应 APK `an.d()` = `this == s`(highlight/poi 分类)。
    // 高亮片段只标记不跳过,不应从剩余时长里扣减。
    // 与 SponsorCategories.POI_HIGHLIGHT 保持一致（这里不加公开常量，避免跨文件耦合）。
    private const val POI_HIGHLIGHT = "poi_highlight"
    private val nonDeductibleCategories = setOf(POI_HIGHLIGHT)

    /**
     * 扣除「实际会被跳过」的时长后的剩余时长。
     *
     * 旧实现无条件扣掉所有 `actionType=skip` 片段，但真实跳过还会被
     * `minSkipDurationSec` 过滤，且总开关/自动/手动策略关闭时根本不跳，于是
     * 显示的剩余时长比实际短。这里把两个条件做成参数。
     *
     * 参数带默认值是为了兼容现有调用点（`BiliSponsorBlockHooks`），它只传前两个参数。
     *
     * @param minSkipDurationMs 最小片段时长阈值，短于它不会被跳过，因此不扣减
     * @param skipEnabled       跳过策略是否开启；关闭时返回原时长
     */
    fun adjustedDuration(
        durationMs: Long,
        segments: List<SponsorSegment>,
        minSkipDurationMs: Long = 0L,
        skipEnabled: Boolean = true,
    ): Long {
        if (durationMs <= 0 || segments.isEmpty() || !skipEnabled) {
            return durationMs
        }

        var deducted = 0L
        var currentEnd = -1L
        segments
            .filter { it.actionType == "skip" && it.category !in nonDeductibleCategories }
            .sortedBy { it.startMs }
            .forEach { segment ->
                // 先 clamp 到视频时长内再过阈值:endMs 远超 durationMs 的片段按「实际会跳的时长」
                // 过滤,而不是按原始长度过阈值、随后又被 clamp 扣减,两者在边界上不一致
                val start = segment.startMs.coerceAtLeast(0L)
                val end = segment.endMs.coerceAtMost(durationMs)
                if (end - start < minSkipDurationMs) {
                    return@forEach
                }
                if (end <= start) {
                    return@forEach
                }
                if (start > currentEnd) {
                    deducted += end - start
                    currentEnd = end
                } else if (end > currentEnd) {
                    deducted += end - currentEnd
                    currentEnd = end
                }
            }

        return (durationMs - deducted).coerceAtLeast(0L)
    }

    /**
     * 在原始进度文本后追加扣减结果。
     *
     * 用 [SpannableStringBuilder] 而不是 buildString：宿主传进来的进度文本往往是 Spanned
     * （带字体等 span），buildString 会把 span 全部丢掉；原文本不是 Spanned 时，
     * 行为与旧实现完全一致。
     *
     * @param adjustedDurationMs 为 0/负数时不追加（调用方用它表示"没有可扣减的片段"）
     */
    fun appendAdjustedDuration(originalText: CharSequence, adjustedDurationMs: Long): CharSequence {
        if (adjustedDurationMs <= 0) {
            return originalText
        }
        val suffix = " (${formatDuration(adjustedDurationMs)})"
        // 只有原文本本身带 span 时才需要 SpannableStringBuilder（它的构造/append 在纯 JVM
        // 单测里是 not mocked，因此不能无条件走这条路）；纯文本直接拼接，行为完全等价。
        return if (originalText is android.text.Spanned) {
            SpannableStringBuilder(originalText).append(suffix)
        } else {
            originalText.toString() + suffix
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms + 999) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
