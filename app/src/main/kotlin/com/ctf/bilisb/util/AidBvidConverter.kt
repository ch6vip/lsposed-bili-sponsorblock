package com.ctf.bilisb.util

object AidBvidConverter {
    private val table = charArrayOf(
        'F', 'c', 'w', 'A', 'P', 'N', 'K', 'T', 'M', 'u', 'g', '3',
        'G', 'V', '5', 'L', 'j', '7', 'E', 'J', 'n', 'H', 'p', 'W',
        's', 'x', '4', 't', 'b', '8', 'h', 'a', 'Y', 'e', 'v', 'i',
        'q', 'B', 'z', '6', 'r', 'k', 'C', 'y', '1', '2', 'm', 'U',
        'S', 'D', 'Q', 'X', '9', 'R', 'd', 'o', 'Z', 'f',
    )

    fun aidToBvid(aid: Long): String {
        val chars = charArrayOf('B', 'V', '1', '0', '0', '0', '0', '0', '0', '0', '0', '0')
        var value = (aid or 2251799813685248L) xor 23442827791579L
        var index = 11
        while (value != 0L && index >= 0) {
            chars[index] = table[(value % 58).toInt()]
            value /= 58
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
