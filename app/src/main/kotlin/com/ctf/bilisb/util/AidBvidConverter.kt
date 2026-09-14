package com.ctf.bilisb.util

object AidBvidConverter {
    private val table = charArrayOf(
        'F', 'c', 'w', 'A', 'P', 'N', 'K', 'T', 'M', 'u', 'g', '3',
        'G', 'V', '5', 'L', 'j', '7', 'E', 'J', 'n', 'H', 'p', 'W',
        's', 'x', '4', 't', 'b', '8', 'h', 'a', 'Y', 'e', 'v', 'i',
        'q', 'B', 'z', '6', 'r', 'k', 'C', 'y', '1', '2', 'm', 'U',
        'S', 'D', 'Q', 'X', '9', 'R', 'd', 'o', 'Z', 'f',
    )

    /** 编码取模基数(与 [table] 长度一致)。 */
    private const val BASE = 58L

    /**
     * aid 转 bvid(与官方 `av2bv` 算法一致)。
     *
     * 边界保护:
     *   - `aid <= 0` 不是合法 avid,直接返回空串(调用方 [com.ctf.bilisb.sponsor.SponsorBlockController]
     *     也在 `aid <= 0` 时提前 return,这里是双保险)。
     *   - aid 超过 51 位可表示范围(≥ 2^51)时,`aid or 2^51` 之后再异或常量会得到**负数**,
     *     而负数取余在 Kotlin 里是负值,直接拿去索引 [table] 会 `IndexOutOfBoundsException`。
     *     所以这里统一用 [java.lang.Long.remainderUnsigned] 取无符号余数,顺带覆盖
     *     `Long.MAX_VALUE` 这类极端输入(结果与官方实现对 2^51 的退化输出一致)。
     */
    fun aidToBvid(aid: Long): String {
        if (aid <= 0L) return ""
        val chars = charArrayOf('B', 'V', '1', '0', '0', '0', '0', '0', '0', '0', '0', '0')
        var value = (aid or 2251799813685248L) xor 23442827791579L
        var index = 11
        while (value != 0L && index >= 0) {
            chars[index] = table[java.lang.Long.remainderUnsigned(value, BASE).toInt()]
            value /= BASE
            index--
        }
        swap(chars, 3, 9)
        swap(chars, 4, 7)
        return String(chars)
    }

    private fun swap(chars: CharArray, left: Int, right: Int) {
        val tmp = chars[left]
        chars[left] = chars[right]
        chars[right] = tmp
    }
}
