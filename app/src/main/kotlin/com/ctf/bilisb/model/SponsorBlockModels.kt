package com.ctf.bilisb.model

data class SponsorSegment(
    val category: String,
    val actionType: String,
    val segment: LongArray,
    val uuid: String,
    val videoDuration: Double,
    val locked: Boolean,
    val votes: Long,
    val description: String = "",
) {
    val startMs: Long get() = segment.getOrNull(0) ?: 0L
    val endMs: Long get() = segment.getOrNull(1) ?: startMs
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class SponsorBlockConfig(
    val serverAddress: String = "https://www.bsbsb.top",
    val cacheTtlMs: Long = 60L * 60_000L,
    val enabled: Boolean = true,
    val autoSkip: Boolean = true,
    val enabledCategories: Set<String> = setOf(
        "sponsor",
        "selfpromo",
        "interaction",
        "intro",
        "outro",
        "preview",
        "music_offtopic",
        "filler",
    ),
    val enabledActionTypes: Set<String> = setOf("skip", "poi", "mute"),
)
