package com.ctf.bilisb.model

data class SponsorBlockSubmission(
    val userId: String,
    val bvid: String,
    val cid: Long,
    val category: String,
    val startMs: Long,
    val endMs: Long,
    val videoDurationMs: Long,
    val epId: Int = 0,
) {
    val isValid: Boolean
        get() = userId.isNotBlank() &&
            bvid.isNotBlank() &&
            cid > 0 &&
            category.isNotBlank() &&
            startMs >= 0 &&
            endMs > startMs &&
            videoDurationMs > 0
}
