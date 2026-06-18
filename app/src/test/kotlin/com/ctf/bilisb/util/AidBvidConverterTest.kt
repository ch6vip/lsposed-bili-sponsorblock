package com.ctf.bilisb.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AidBvidConverterTest {
    @Test
    fun convertsKnownAidToBvid() {
        assertEquals("BV17x411w7KC", AidBvidConverter.aidToBvid(170001))
    }
}
