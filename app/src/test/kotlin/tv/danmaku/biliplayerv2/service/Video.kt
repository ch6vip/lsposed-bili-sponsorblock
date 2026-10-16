package tv.danmaku.biliplayerv2.service

/** JVM fixtures with the exact 3.20.4/6.5.0 binary names and accessor signatures. */
@Suppress("ClassName", "FunctionName")
class Video {
    class b(private val avid: Long, private val cid: Long) {
        fun c(): Long = avid
        fun d(): Long = cid
    }

    class a(@JvmField val a: Long, @JvmField val b: Long)

    // e is the old video interface name, reused for interaction metadata in 3.20.4.
    class e(private val params: a? = null) {
        fun z(): a? = params
    }

    class f(private val details: b?) {
        fun D(): b? = details
        fun T(): e = error("Interaction metadata must never be used for aid/cid")
    }
}
