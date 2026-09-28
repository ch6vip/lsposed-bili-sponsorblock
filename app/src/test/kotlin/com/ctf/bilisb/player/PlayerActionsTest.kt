package com.ctf.bilisb.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerActionsTest {
    @Test
    fun seekPositionClampsNegativeToZero() {
        assertEquals(0, PlayerActions.seekPositionToInt(-1L))
        assertEquals(0, PlayerActions.seekPositionToInt(Long.MIN_VALUE))
    }

    @Test
    fun seekPositionKeepsNormalMillis() {
        assertEquals(1_800_000, PlayerActions.seekPositionToInt(1_800_000L))
        assertEquals(Int.MAX_VALUE, PlayerActions.seekPositionToInt(Int.MAX_VALUE.toLong()))
    }

    @Test
    fun seekPositionClampsOverflowInsteadOfWrappingNegative() {
        assertEquals(Int.MAX_VALUE, PlayerActions.seekPositionToInt(Int.MAX_VALUE.toLong() + 1L))
        assertEquals(Int.MAX_VALUE, PlayerActions.seekPositionToInt(Long.MAX_VALUE))
    }
}
