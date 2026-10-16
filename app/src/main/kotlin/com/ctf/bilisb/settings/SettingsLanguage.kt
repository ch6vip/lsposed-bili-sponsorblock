package com.ctf.bilisb.settings

import java.util.Locale

/** Process-local rendering choice; persisted and synchronized by SettingsCodec/SettingsWriter. */
object SettingsLanguage {
    const val SYSTEM = "system"
    const val CHINESE = "zh-CN"
    const val ENGLISH = "en"
    val choices = listOf(SYSTEM, CHINESE, ENGLISH)

    @Volatile
    var current: String = SYSTEM
        private set

    fun sanitize(value: String?): String = value?.takeIf { it in choices } ?: SYSTEM

    fun apply(value: String?) {
        current = sanitize(value)
    }

    fun locale(value: String = current): Locale? = when (sanitize(value)) {
        CHINESE -> Locale.SIMPLIFIED_CHINESE
        ENGLISH -> Locale.ENGLISH
        else -> null
    }
}
