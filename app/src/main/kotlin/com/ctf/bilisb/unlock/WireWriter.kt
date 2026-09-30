package com.ctf.bilisb.unlock

import java.io.ByteArrayOutputStream

/**
 * 极简 protobuf wire-format 写入器（解锁 U4，纯 JVM 可单测）。
 *
 * 为什么不用宿主 Builder 反射：宿主的 protobuf 版本对嵌套消息只生成 builder 重载
 * （addStreamList(Stream$b)），Builder 获取链路（newBuilderForType/dynamicMethod）跨
 * 版本不稳；而 **wire bytes + 宿主类 parseFrom** 只依赖字段号（运行时实测固化在
 * bilisb_unlock.proto）——U2 已证明自备 schema 与宿主字节级兼容。
 *
 * proto3 语义：标量字段默认值不写（与宿主序列化一致）；消息子字段**显式写空**（置位存在性）。
 */
class WireWriter {

    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    private fun varint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7fL.inv() == 0L) {
                out.write(x.toInt())
                return
            }
            out.write(((x and 0x7fL) or 0x80L).toInt())
            x = x ushr 7
        }
    }

    private fun tag(field: Int, wireType: Int) = varint((field.toLong() shl 3) or wireType.toLong())

    fun int32Field(field: Int, v: Int) {
        if (v == 0) return
        tag(field, 0)
        varint(v.toLong())
    }

    fun int64Field(field: Int, v: Long) {
        if (v == 0L) return
        tag(field, 0)
        varint(v)
    }

    fun boolField(field: Int, v: Boolean) {
        if (!v) return
        tag(field, 0)
        varint(1)
    }

    fun stringField(field: Int, v: String) {
        if (v.isEmpty()) return
        bytesField(field, v.toByteArray(Charsets.UTF_8))
    }

    /** 字节/字符串字段：空值跳过。 */
    fun bytesField(field: Int, v: ByteArray) {
        if (v.isEmpty()) return
        tag(field, 2)
        varint(v.size.toLong())
        out.write(v)
    }

    /** 消息子字段：**始终写入**（空消息也要置位存在性，如 business/view_info 清弹窗）。 */
    fun messageField(field: Int, payload: ByteArray) {
        tag(field, 2)
        varint(payload.size.toLong())
        out.write(payload)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * 解锁链路用到的消息字节构造（字段号运行时实测，见 bilisb_unlock.proto）。
 */
object UnlockWire {

    fun buildVodInfoBytes(data: PlayurlData): ByteArray {
        val w = WireWriter()
        w.int32Field(1, data.quality)
        w.stringField(2, data.format)
        w.int64Field(3, data.timelength)
        w.int32Field(4, data.videoCodecid)
        for (v in data.videos) w.messageField(5, buildStreamBytes(v))
        for (a in data.audios) w.messageField(6, buildDashItemBytes(a))
        return w.toByteArray()
    }

    /**
     * Stream{stream_info=1, oneof content{dash_video=2}}。
     * U4.6 实测：真实响应每条 Stream 都带 stream_info（quality/format/description/intact），
     * 缺失会被播放器选流逻辑忽略（表现 = 响应被接受但无限缓冲）——必须携带。
     */
    private fun buildStreamBytes(v: DashTrack): ByteArray {
        val w = WireWriter()
        w.messageField(1, buildStreamInfoBytes(v))
        w.messageField(2, buildDashVideoBytes(v))
        return w.toByteArray()
    }

    private fun buildStreamInfoBytes(v: DashTrack): ByteArray {
        val w = WireWriter()
        val meta = v.meta
        w.int32Field(1, meta?.quality ?: v.id)
        w.stringField(2, meta?.format ?: "")
        w.stringField(3, meta?.description ?: "")
        w.boolField(8, true)  // intact：流完整可播
        w.stringField(11, meta?.newDescription ?: "")
        return w.toByteArray()
    }

    private fun buildDashVideoBytes(v: DashTrack): ByteArray {
        val w = WireWriter()
        w.stringField(1, v.baseUrl)
        for (bk in v.backupUrls) w.stringField(2, bk)
        w.int32Field(3, v.bandwidth)
        w.int32Field(4, v.codecid)
        w.stringField(5, v.md5)
        w.int64Field(6, v.size)
        return w.toByteArray()
    }

    private fun buildDashItemBytes(a: DashTrack): ByteArray {
        val w = WireWriter()
        w.int32Field(1, a.id)
        w.stringField(2, a.baseUrl)
        for (bk in a.backupUrls) w.stringField(3, bk)
        w.int32Field(4, a.bandwidth)
        w.int32Field(5, a.codecid)
        w.stringField(6, a.md5)
        w.int64Field(7, a.size)
        return w.toByteArray()
    }

    /** PlayArc{aid=2, cid=3}。 */
    fun buildPlayArcBytes(aid: Long, cid: Long): ByteArray {
        val w = WireWriter()
        w.int64Field(2, aid)
        w.int64Field(3, cid)
        return w.toByteArray()
    }

    /**
     * PGC Any 载荷：PlayViewReply{video_info=1, business=3, view_info=5}——
     * view_info 显式空（清 area_limit 弹窗）；video_info 携带漫游流——
     * U4.5 实测：只填 unite 级 vod_info 播放器不读，PGC 内容的流要从
     * supplement 内的 PGC video_info 读（宿主 6.6.0 实机行为）。
     */
    fun buildPgcPayloadBytes(data: PlayurlData): ByteArray {
        val w = WireWriter()
        w.messageField(1, buildVodInfoBytes(data))     // video_info：漫游流
        w.messageField(3, WireWriter().toByteArray())  // business（is_preview=false 走 builder 补丁）
        w.messageField(5, WireWriter().toByteArray())  // view_info 空 = 无弹窗
        return w.toByteArray()
    }

    /** google.protobuf.Any{type_url=1, value=2}。 */
    fun buildAnyBytes(typeUrl: String, value: ByteArray): ByteArray {
        val w = WireWriter()
        w.stringField(1, typeUrl)
        w.bytesField(2, value)
        return w.toByteArray()
    }
}
