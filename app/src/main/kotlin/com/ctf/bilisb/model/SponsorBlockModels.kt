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

    /**
     * data class 自动生成的 equals/hashCode 对 [LongArray] 用引用比较,契约被破坏:
     * 任何把 SponsorSegment 放进 Set / 做 == / copy 对比的地方都会出错。
     * 这里按内容比较 segment 数组,其余字段沿用 data class 语义。
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SponsorSegment) return false
        return category == other.category &&
            actionType == other.actionType &&
            segment.contentEquals(other.segment) &&
            uuid == other.uuid &&
            videoDuration == other.videoDuration &&
            locked == other.locked &&
            votes == other.votes &&
            description == other.description
    }

    override fun hashCode(): Int {
        var result = category.hashCode()
        result = 31 * result + actionType.hashCode()
        result = 31 * result + segment.contentHashCode()
        result = 31 * result + uuid.hashCode()
        result = 31 * result + videoDuration.hashCode()
        result = 31 * result + locked.hashCode()
        result = 31 * result + votes.hashCode()
        result = 31 * result + description.hashCode()
        return result
    }
}

data class SponsorBlockConfig(
    val serverAddress: String = DEFAULT_SERVER_ADDRESS,
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
) {
    companion object {
        /**
         * 默认服务器单一来源:SettingsKeys.DEFAULT_SERVER 引用这里,避免两处字面量漂移。
         */
        const val DEFAULT_SERVER_ADDRESS = "https://bsbsb.top"
    }
}
