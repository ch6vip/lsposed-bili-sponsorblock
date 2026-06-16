package com.ctf.bilisb.model

data class SponsorBlockQuery(
    val bvid: String,
    val cid: Long,
    val actionType: String = "skip",
)
