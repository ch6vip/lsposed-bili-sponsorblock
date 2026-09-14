package com.ctf.bilisb.net

import com.ctf.bilisb.model.SponsorBlockConfig
import com.ctf.bilisb.model.SponsorBlockSubmission
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockClientTest {
    // 注入空日志出口:单测里 android.util.Log 未被 mock,触碰到会抛 RuntimeException。
    private val client = SponsorBlockClient(
        config = SponsorBlockConfig(
            enabledCategories = setOf("sponsor", "poi_highlight", "intro"),
            enabledActionTypes = setOf("skip", "poi", "mute"),
        ),
        logger = {},
    )

    @After
    fun tearDown() {
        client.close()
    }

    @Test
    fun endpointForBvidUsesFirstFourHexCharsOfSha256() {
        assertEquals(
            "https://bsbsb.top/api/skipSegments/5759",
            client.endpointForBvid("BV14741127BN"),
        )
        // 结尾不带任何 query(真机实测带 ?videoID= / ?cid= 会 400)。
        assertFalse(client.endpointForBvid("BV14741127BN").contains("?"))
    }

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

    @Test
    fun dropsSegmentsWithDisabledCategoryOrActionType() {
        val raw = """
            [
              {"videoID": "BV1", "segments": [
                {"segment": [0.0, 5.0], "category": "sponsor", "actionType": "skip", "UUID": "kept"},
                {"segment": [10.0, 15.0], "category": "outro", "actionType": "skip", "UUID": "category-off"},
                {"segment": [20.0, 25.0], "category": "sponsor", "actionType": "full", "UUID": "action-off"}
              ]}
            ]
        """.trimIndent()

        val segments = client.parseSegmentsForVideo("BV1", raw)

        assertEquals(listOf("kept"), segments.map { it.uuid })
    }

    @Test
    fun dropsSegmentsFailingSanityChecks() {
        val raw = """
            [
              {"videoID": "BV1", "segments": [
                {"segment": [-1.0, 10.0], "category": "sponsor", "actionType": "skip", "UUID": "negative-start"},
                {"segment": [5.0, 5.0], "category": "sponsor", "actionType": "skip", "UUID": "zero-length"},
                {"segment": [10.0, 10.1], "category": "sponsor", "actionType": "skip", "UUID": "too-short"},
                {"segment": [30.0, 1e400], "category": "sponsor", "actionType": "skip", "UUID": "infinite-end"},
                {"segment": [1.0, 40.0], "category": "sponsor", "actionType": "skip", "UUID": "beyond-duration", "videoDuration": 30.0},
                {"segment": [1.0, 20.0], "category": "sponsor", "actionType": "skip", "UUID": "within-duration", "videoDuration": 30.0},
                {"segment": [0.0, 5.0], "category": "sponsor", "actionType": "skip", "UUID": "kept"}
              ]}
            ]
        """.trimIndent()

        val segments = client.parseSegmentsForVideo("BV1", raw)

        // 按 startMs 排序:kept(0ms) < within-duration(1000ms)
        assertEquals(listOf("kept", "within-duration"), segments.map { it.uuid })
        assertEquals(listOf(0L, 1_000L), segments.map { it.startMs })
    }

    @Test
    fun treatsJsonNullFieldsAsMissingInsteadOfLiteralNull() {
        val raw = """
            [
              {"videoID": "BV1", "segments": [
                {"segment": [0.0, 5.0], "category": null, "actionType": null, "UUID": "nulls-fall-back", "description": null},
                {"segment": [10.0, 15.0], "category": "sponsor", "actionType": "skip", "UUID": null},
                {"segment": [20.0, 25.0], "category": "sponsor", "actionType": "skip"}
              ]}
            ]
        """.trimIndent()

        val segments = client.parseSegmentsForVideo("BV1", raw)

        // 只有第一条能留下:category/actionType 的 null 退回默认值,不再变成字面量 "null";
        // UUID 为 null 或缺键的片段必须丢弃。
        assertEquals(listOf("nulls-fall-back"), segments.map { it.uuid })
        assertEquals("sponsor", segments.first().category)
        assertEquals("skip", segments.first().actionType)
        assertEquals("", segments.first().description)
    }

    @Test
    fun stopsAfterFirstBucketMatchingTargetVideo() {
        val raw = """
            [
              {"videoID": "BV1", "segments": [
                {"segment": [0.0, 5.0], "category": "sponsor", "actionType": "skip", "UUID": "first-bucket"}
              ]},
              {"videoID": "BV1", "segments": [
                {"segment": [10.0, 15.0], "category": "sponsor", "actionType": "skip", "UUID": "second-bucket"}
              ]}
            ]
        """.trimIndent()

        // 命中目标 videoID 后立即 break:后面同名桶不再解析。
        val segments = client.parseSegmentsForVideo("BV1", raw)

        assertEquals(listOf("first-bucket"), segments.map { it.uuid })
    }

    @Test
    fun buildSubmitUrlKeepsLegacyGetShapeAndEncodesValues() {
        val url = client.buildSubmitUrl(
            submission(userId = "user 1", category = "a b&c=d", epId = 88),
        )

        assertTrue(url.startsWith("https://bsbsb.top/api/skipSegments?"))
        assertTrue(url.contains("userID=user+1"))
        assertTrue(url.contains("videoID=BV14741127BN"))
        assertTrue(url.contains("cid=123"))
        assertTrue(url.contains("category=a+b%26c%3Dd"))
        assertTrue(url.contains("startTime=15.000"))
        assertTrue(url.contains("endTime=20.000"))
        assertTrue(url.contains("videoDuration=60.000"))
        assertTrue(url.contains("epId=88"))
    }

    @Test
    fun buildSubmitBodyCarriesOfficialPostFieldsAndEncodesValues() {
        val category = "赞助 & 广告=测试"
        val body = client.buildSubmitBody(submission(userId = "user 1", category = category))

        assertTrue(body.startsWith("userID=user+1&"))
        assertTrue(body.contains("&userAgent=Bili2233%2F1.0+%28LSPosed%29&"))
        assertTrue(body.contains("&videoID=BV14741127BN&"))
        assertTrue(body.contains("&cid=123&"))
        assertTrue(body.contains("&actionType=skip&"))
        assertTrue(body.contains("&startTime=15.000&"))
        assertTrue(body.contains("&endTime=20.000&"))
        assertTrue(body.contains("&videoDuration=60.000&"))
        assertTrue(body.contains("&service=bilibili"))
        assertFalse(body.contains("epId="))
        // 中文/空格/&/= 必须 percent-encode,不能原样出现。
        assertFalse(body.contains("赞助"))
        assertTrue(body.contains("category=" + URLEncoder.encode(category, "UTF-8")))
    }

    @Test
    fun buildSubmitBodyOmitsCidAndAppendsEpIdWhenPresent() {
        assertFalse(client.buildSubmitBody(submission(cid = 0L)).contains("cid="))
        assertTrue(client.buildSubmitBody(submission(epId = 88)).contains("&epId=88"))
    }

    @Test
    fun anyTwoHundredStatusCountsAsSuccess() {
        assertTrue(SponsorBlockClient.FetchResult(200, null, emptyList()).isSuccess)
        assertTrue(SponsorBlockClient.FetchResult(299, null, emptyList()).isSuccess)
        assertFalse(SponsorBlockClient.FetchResult(404, null, emptyList()).isSuccess)
        assertTrue(SponsorBlockClient.SubmitResult(200, null).isSuccess)
        assertTrue(SponsorBlockClient.SubmitResult(201, null).isSuccess)
        assertFalse(SponsorBlockClient.SubmitResult(405, null).isSuccess)
        // 默认方法为 POST,新增字段不能破坏原有 2 参数构造。
        assertEquals("POST", SponsorBlockClient.SubmitResult(200, null).method)
    }

    @Test
    fun retryRequestDoesNotRetryNonIoExceptions() {
        var calls = 0
        val failure = client.retryRequest(
            maxRetries = 3,
            onFailure = { e -> "failed:${e.javaClass.simpleName}" },
        ) {
            calls += 1
            throw IllegalArgumentException("bad argument")
        }

        assertEquals(1, calls)
        assertEquals("failed:IllegalArgumentException", failure)
    }

    @Test
    fun retryRequestDoesNotRetryMalformedUrlEvenThoughItIsAnIoException() {
        var calls = 0
        val failure = client.retryRequest(
            maxRetries = 3,
            onFailure = { e -> "failed:${e.javaClass.simpleName}" },
        ) {
            calls += 1
            throw MalformedURLException("bad url")
        }

        assertEquals(1, calls)
        assertEquals("failed:MalformedURLException", failure)
    }

    @Test
    fun retryRequestRetriesIoExceptionsUntilMaxAttempts() {
        var calls = 0
        val failure = client.retryRequest(
            maxRetries = 2,
            onFailure = { e -> "failed:${e.message}" },
        ) {
            calls += 1
            throw SocketTimeoutException("timeout $calls")
        }

        assertEquals(2, calls)
        assertEquals("failed:timeout 2", failure)
    }

    @Test
    fun retryRequestRestoresInterruptFlagAndStopsImmediately() {
        var calls = 0
        try {
            val failure = client.retryRequest<String>(
                maxRetries = 3,
                onFailure = { "interrupted" },
            ) {
                calls += 1
                throw InterruptedException("stop")
            }

            assertEquals(1, calls)
            assertEquals("interrupted", failure)
            assertTrue("中断标记必须被恢复", Thread.currentThread().isInterrupted)
        } finally {
            // 清掉中断标记,避免污染后续用例。
            Thread.interrupted()
        }
    }

    private fun submission(
        userId: String = "user-1",
        bvid: String = "BV14741127BN",
        cid: Long = 123L,
        category: String = "sponsor",
        startMs: Long = 15_000L,
        endMs: Long = 20_000L,
        videoDurationMs: Long = 60_000L,
        epId: Int = 0,
    ) = SponsorBlockSubmission(
        userId = userId,
        bvid = bvid,
        cid = cid,
        category = category,
        startMs = startMs,
        endMs = endMs,
        videoDurationMs = videoDurationMs,
        epId = epId,
    )
}
