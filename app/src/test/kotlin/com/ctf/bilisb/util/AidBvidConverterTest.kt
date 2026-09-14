package com.ctf.bilisb.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AidBvidConverterTest {
    @Test
    fun convertsKnownAidToBvid() {
        assertEquals("BV17x411w7KC", AidBvidConverter.aidToBvid(170001))
    }

    /** 真值来自独立实现(官方 av2bv)验证,见 docs 里的算法说明。 */
    @Test
    fun convertsReferenceAidsToBvid() {
        assertEquals("BV14741127BN", AidBvidConverter.aidToBvid(98_935_548))
    }

    /** 51 位可表示范围的上界。 */
    @Test
    fun convertsMaxEncodableAidToBvid() {
        assertEquals("BV1aPPTfmvQq", AidBvidConverter.aidToBvid((1L shl 51) - 1L))
    }

    /** 超出 51 位:aid|2^51 之后异或会变成负数,必须走无符号取余,否则数组越界崩溃。 */
    @Test
    fun convertsAidBeyondFiftyOneBitsToBvid() {
        assertEquals("BV1xx411c7mX", AidBvidConverter.aidToBvid(1L shl 51))
    }

    /** 极端输入:Long.MAX_VALUE 不能崩(旧实现这里会 IndexOutOfBoundsException)。 */
    @Test
    fun convertsLongMaxValueWithoutCrash() {
        assertEquals("BHWkGuhRkyzb", AidBvidConverter.aidToBvid(Long.MAX_VALUE))
    }

    @Test
    fun returnsEmptyStringForNonPositiveAid() {
        assertEquals("", AidBvidConverter.aidToBvid(0L))
        assertEquals("", AidBvidConverter.aidToBvid(-1L))
    }
}
