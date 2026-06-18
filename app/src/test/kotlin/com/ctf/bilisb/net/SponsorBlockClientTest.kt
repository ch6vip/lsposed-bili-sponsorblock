package com.ctf.bilisb.net

import com.ctf.bilisb.model.SponsorBlockConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockClientTest {
    private val client = SponsorBlockClient(
        SponsorBlockConfig(
            enabledCategories = setOf("sponsor", "poi_highlight", "intro"),
            enabledActionTypes = setOf("skip", "poi", "mute"),
        )
    )

    @Test
    fun parsesSegmentsForTargetVideoAndFiltersInvalidOnes() {
        val raw = """
            [
              {
                "videoID": "BV17x411w7KC",
                "segments": [
                  {"segment": [15.0, 20.0], "category": "poi_highlight", "actionType": "poi", "UUID": "u2"},
                  {"segment": [0.0, 10.5], "category": "sponsor", "actionType": "skip", "UUID": "u1"},
                  {"segment": [30.0, 35.0], "category": "intro", "actionType": "mute", "UUID": "u3"},
                  {"segment": [40.0, 39.0], "category": "sponsor", "actionType": "skip", "UUID": "bad"}
                ]
              },
              {
                "videoID": "BV_OTHER",
                "segments": [
                  {"segment": [1.0, 2.0], "category": "sponsor", "actionType": "skip", "UUID": "other"}
                ]
              }
            ]
        """.trimIndent()

        val segments = client.parseSegmentsForVideo("BV17x411w7KC", raw)

        assertEquals(listOf("u1", "u2", "u3"), segments.map { it.uuid })
        assertEquals(listOf(0L, 15_000L, 30_000L), segments.map { it.startMs })
        assertEquals(listOf(10_500L, 20_000L, 35_000L), segments.map { it.endMs })
    }

    @Test
    fun returnsEmptyListForBlankPayload() {
        assertTrue(client.parseSegmentsForVideo("BV17x411w7KC", " ").isEmpty())
    }
}
