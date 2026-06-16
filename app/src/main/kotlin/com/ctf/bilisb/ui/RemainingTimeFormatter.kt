package com.ctf.bilisb.ui

import com.ctf.bilisb.model.SponsorSegment
import java.util.Locale

object RemainingTimeFormatter {
    fun adjustedDuration(totalMs: Long, segments: List<SponsorSegment>): Long {
        if (totalMs <= 0 || segments.isEmpty()) {
            return totalMs
        }

        var deducted = 0L
        var currentEnd = -1L
        segments
            .filter { it.actionType == "skip" }
            .sortedBy { it.startMs }
            .forEach { segment ->
                val start = segment.startMs.coerceAtLeast(0L)
                val end = segment.endMs.coerceAtMost(totalMs)
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

        return (totalMs - deducted).coerceAtLeast(0L)
    }

    fun appendAdjustedDuration(originalText: CharSequence, adjustedDurationMs: Long): CharSequence {
        if (adjustedDurationMs <= 0) {
            return originalText
        }
        return buildString {
            append(originalText)
            append(" (")
            append(formatDuration(adjustedDurationMs))
            append(")")
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
