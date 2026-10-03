package com.ctf.bilisb.unlock

/**
 * protobuf wire 格式的**只读解析与定点拼接**工具（选集面板 tab 注入用）。
 *
 * 与 [WireWriter]（自底向上构建）互补：这里处理「拿到宿主实拍 bytes，在指定路径上
 * 替换/追加子消息，其余字节原样保留」的场景——未知字段零损（tag+载荷整体搬运），
 * 不依赖任何 schema 库。
 */
object WireSplice {

    /** 单个 wire 元素：保留原始字节（tag + 载荷/varint）。 */
    data class Elem(val field: Int, val wireType: Int, val raw: ByteArray) {
    /** raw[0..] 是 tag+载荷；tag 自身的 varint 字节数。 */
    private fun tagSize(): Int {
        var i = 0
        while (i < raw.size && raw[i].toInt() and 0x80 != 0) i++
        return i + 1
    }

    /** wt0 的 varint 值（其余类型无意义返回 0）。 */
    fun varint(): Long {
        if (wireType != 0) return 0
        var r = 0L
        var s = 0
        for (k in tagSize() until raw.size) {
            r = r or ((raw[k].toInt() and 0x7f).toLong() shl s)
            if (raw[k].toInt() and 0x80 == 0) break
            s += 7
        }
        return r
    }

    /** wt2 的载荷（不含 tag/长度前缀）。 */
    fun payload(): ByteArray {
        if (wireType != 2) return ByteArray(0)
        var i = tagSize()
        var len = 0
        var sh = 0
        while (i < raw.size) {
            val b = raw[i].toInt(); i++
            len = len or ((b and 0x7f) shl sh)
            if (b and 0x80 == 0) break
            sh += 7
        }
        return raw.copyOfRange(i, (i + len).coerceAtMost(raw.size))
    }
    }

    fun parse(bytes: ByteArray): List<Elem> {
        val out = mutableListOf<Elem>()
        var i = 0
        while (i < bytes.size) {
            val start = i
            var tag = 0L
            var s = 0
            while (i < bytes.size) {
                val b = bytes[i].toInt(); i++
                tag = tag or ((b and 0x7f).toLong() shl s)
                if (b and 0x80 == 0) break
                s += 7
            }
            val field = (tag ushr 3).toInt()
            val wt = (tag and 0x7).toInt()
            when (wt) {
                0 -> while (i < bytes.size) {
                    if (bytes[i].toInt() and 0x80 == 0) { i++; break }
                    i++
                }
                1 -> i += 8
                2 -> {
                    var len = 0
                    var sh = 0
                    while (i < bytes.size) {
                        val b = bytes[i].toInt(); i++
                        len = len or ((b and 0x7f) shl sh)
                        if (b and 0x80 == 0) break
                        sh += 7
                    }
                    i += len
                }
                5 -> i += 4
                else -> throw IllegalArgumentException("wire type $wt @$i")
            }
            out.add(Elem(field, wt, bytes.copyOfRange(start, i)))
        }
        return out
    }

    fun emit(elems: List<Elem>): ByteArray {
        var n = 0
        for (e in elems) n += e.raw.size
        val out = ByteArray(n)
        var i = 0
        for (e in elems) { e.raw.copyInto(out, i); i += e.raw.size }
        return out
    }

    fun message(field: Int, payload: ByteArray): ByteArray {
        val w = WireWriter()
        w.messageField(field, payload)
        return w.toByteArray()
    }

    fun varintElem(field: Int, v: Long): ByteArray {
        val w = WireWriter()
        w.int64Field(field, v)
        return w.toByteArray()
    }

    /**
     * 递归变换：把 [bytes] 里 field==[fieldNumber] 的每个 wt2 元素的载荷交给 [transform]，
     * 其余元素原样保留。[transform] 返回 null 表示删除该元素。
     */
    fun transformMessage(
        bytes: ByteArray,
        fieldNumber: Int,
        transform: (ByteArray) -> ByteArray?,
    ): ByteArray {
        val out = mutableListOf<Elem>()
        for (e in parse(bytes)) {
            if (e.field == fieldNumber && e.wireType == 2) {
                val replaced = transform(e.payload())
                if (replaced != null) out.add(Elem(fieldNumber, 2, message(fieldNumber, replaced)))
            } else {
                out.add(e)
            }
        }
        return emit(out)
    }

    /** 找第一个 field==[fieldNumber] 的 wt2 载荷（找不到返回 null）。 */
    fun firstMessage(bytes: ByteArray, fieldNumber: Int): ByteArray? =
        parse(bytes).firstOrNull { it.field == fieldNumber && it.wireType == 2 }?.payload()

    /** field==[fieldNumber] 的 wt2 载荷列表。 */
    fun allMessages(bytes: ByteArray, fieldNumber: Int): List<ByteArray> =
        parse(bytes).filter { it.field == fieldNumber && it.wireType == 2 }.map { it.payload() }

    /** 是否存在 field==[fieldNumber] 且载荷首字段==[firstFieldValue] 的元素（Module.type 判别用）。 */
    fun hasMessageWithFirstVarint(bytes: ByteArray, fieldNumber: Int, firstFieldValue: Long): Boolean =
        allMessages(bytes, fieldNumber).any { msg ->
            parse(msg).firstOrNull()?.let { it.field == 1 && it.wireType == 0 && it.varint() == firstFieldValue } == true
        }
}
