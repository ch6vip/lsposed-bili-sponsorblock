package com.ctf.bilisb.ui

import android.widget.TextView
import java.util.WeakHashMap

object ProgressTextDecorator {
    private val decoratedViews = WeakHashMap<TextView, CharSequence>()

    fun applyIfNeeded(textView: TextView, adjustedDurationMs: Long, text: CharSequence): CharSequence {
        if (adjustedDurationMs <= 0) {
            return text
        }

        val decorated = RemainingTimeFormatter.appendAdjustedDuration(text, adjustedDurationMs)
        decoratedViews[textView] = decorated
        return decorated
    }

    fun isSameDecoration(textView: TextView, text: CharSequence): Boolean {
        return decoratedViews[textView] == text
    }
}
