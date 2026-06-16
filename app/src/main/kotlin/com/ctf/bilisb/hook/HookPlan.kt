package com.ctf.bilisb.hook

object HookPlan {
    const val TARGET_PACKAGE = "tv.danmaku.bili"

    val playerHooks = listOf(
        "player container create",
        "progress bar draw",
        "progress text update",
        "player play/pause/seek",
        "mini play start",
    )
}

