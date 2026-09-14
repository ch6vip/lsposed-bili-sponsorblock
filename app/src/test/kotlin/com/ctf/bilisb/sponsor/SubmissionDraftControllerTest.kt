package com.ctf.bilisb.sponsor

import com.ctf.bilisb.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubmissionDraftControllerTest {
    private val clock = FakeClock()

    @Test
    fun firstMarkStartsDraftAndSecondMarkBuildsSubmission() {
        val controller = SubmissionDraftController(clock::now)
        val state = state()

        assertNull(controller.markOrBuildSubmission("user", state, 10_000L))

        val submission = controller.markOrBuildSubmission("user", state, 25_000L)

        assertNotNull(submission)
        assertEquals(10_000L, submission!!.startMs)
        assertEquals(25_000L, submission.endMs)
        assertEquals("sponsor", submission.category)
        assertEquals("BV17x411w7KC", submission.bvid)
        assertEquals(123L, submission.cid)
    }

    /** 草稿取出后即被消费:第三次点按应当重新记起点,而不是拼出第二个片段。 */
    @Test
    fun draftIsConsumedAfterBuildingSubmission() {
        val controller = SubmissionDraftController(clock::now)
        val state = state()

        controller.markOrBuildSubmission("user", state, 10_000L)
        controller.markOrBuildSubmission("user", state, 20_000L)

        assertNull(controller.markOrBuildSubmission("user", state, 30_000L))
    }

    /** 超过 10 分钟的草稿必须丢弃并重新记起点(否则会把两个不相关的位置当成片段)。 */
    @Test
    fun discardsDraftOlderThanTenMinutes() {
        val controller = SubmissionDraftController(clock::now)
        val state = state()

        controller.markOrBuildSubmission("user", state, 10_000L)
        clock.advanceBy(10L * 60_000L + 1L)

        assertNull(controller.markOrBuildSubmission("user", state, 25_000L))

        // 新草稿的起点是 25_000,而不是旧的 10_000。
        val submission = controller.markOrBuildSubmission("user", state, 30_000L)
        assertEquals(25_000L, submission!!.startMs)
        assertEquals(30_000L, submission.endMs)
    }

    /** 恰好 10 分钟仍算有效(边界内)。 */
    @Test
    fun keepsDraftAtExactlyTenMinutes() {
        val controller = SubmissionDraftController(clock::now)
        val state = state()

        controller.markOrBuildSubmission("user", state, 10_000L)
        clock.advanceBy(10L * 60_000L)

        assertNotNull(controller.markOrBuildSubmission("user", state, 20_000L))
    }

    /** 两次标记落在同一位置 → 零长片段,丢弃并重新记起点。 */
    @Test
    fun discardsZeroLengthDraft() {
        val controller = SubmissionDraftController(clock::now)
        val state = state()

        controller.markOrBuildSubmission("user", state, 10_000L)

        assertNull(controller.markOrBuildSubmission("user", state, 10_000L))
        assertNotNull(controller.markOrBuildSubmission("user", state, 12_000L))
    }

    /** 终点越过已知视频总时长 → 丢弃草稿(多半是 state 已经被切集覆盖)。 */
    @Test
    fun discardsDraftBeyondKnownVideoDuration() {
        val controller = SubmissionDraftController(clock::now)
        val state = state(durationMs = 15_000L)

        controller.markOrBuildSubmission("user", state, 10_000L)

        assertNull(controller.markOrBuildSubmission("user", state, 20_000L))
    }

    /** 总时长未知(0)时不做越界判定。 */
    @Test
    fun acceptsDraftWhenDurationUnknown() {
        val controller = SubmissionDraftController(clock::now)
        val state = state(durationMs = 0L)

        controller.markOrBuildSubmission("user", state, 10_000L)

        assertNotNull(controller.markOrBuildSubmission("user", state, 20_000L))
    }

    /** clear(videoKey):切集时用旧 key 清掉不属于新视频的草稿。 */
    @Test
    fun clearDropsDraftByVideoKey() {
        val controller = SubmissionDraftController(clock::now)
        val state = state()

        controller.markOrBuildSubmission("user", state, 10_000L)
        controller.clear("BV17x411w7KC:123")

        // 草稿已被清掉 → 下一次点按又是「记起点」。
        assertNull(controller.markOrBuildSubmission("user", state, 25_000L))
        assertNotNull(controller.markOrBuildSubmission("user", state, 30_000L))
    }

    @Test
    fun clearIgnoresEmptyKey() {
        val controller = SubmissionDraftController(clock::now)
        val state = state()

        controller.markOrBuildSubmission("user", state, 10_000L)
        controller.clear("")

        assertNotNull(controller.markOrBuildSubmission("user", state, 20_000L))
    }

    private fun state(
        bvid: String = "BV17x411w7KC",
        cid: Long = 123L,
        durationMs: Long = 60_000L,
    ) = PlayerState(
        aid = 170001L,
        bvid = bvid,
        cid = cid,
        durationMs = durationMs,
        currentPositionMs = 0L,
    )

    private class FakeClock {
        private var current = 1_000L
        fun now(): Long = current
        fun advanceBy(deltaMs: Long) {
            current += deltaMs
        }
    }
}
