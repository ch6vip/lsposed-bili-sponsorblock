package com.ctf.bilisb.player

data class PlayerState(
    val aid: Long,
    val bvid: String,
    val cid: Long,
    val durationMs: Long,
    val currentPositionMs: Long,
) {
    val hasVideoId: Boolean get() = bvid.isNotBlank() && cid > 0
}
