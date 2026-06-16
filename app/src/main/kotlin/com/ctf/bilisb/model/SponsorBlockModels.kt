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
}

data class SponsorBlockConfig(
    val serverAddress: String = "https://www.bsbsb.top",
    val enabled: Boolean = true,
    val autoSkip: Boolean = true,
)

