package com.bilibili.ship.theseus.ogv.intro.floatlayer.bean

data class Area(val id: Long, val name: String)
data class Publish(val pubTime: String, val pubTimeShow: String, val isStarted: Boolean, val isFinish: Boolean,
    val weekday: Int, val releaseDateShow: String, val timeLengthShow: String, val unknowPubDate: Int)
data class Style(val id: Long, val url: String, val name: String)
data class Actor(val title: String, val info: String)
data class Staff(val title: String, val info: String)
data class Rating(val score: Float, val count: String)
data class OGVIntroInfoVo(val seasonId: Long, val mediaType: Int, val mediaId: Long,
    val cover: String, val mediaTitle: String, val seasonTitle: String, val typeName: String,
    val areas: List<Area>, val publish: Publish, val badge: Any?, val alias: String,
    val originName: String, val styles: List<Style>, val celebrities: List<Any>,
    val actor: Actor?, val staff: Staff?, val evaluate: String, val rating: Rating)
