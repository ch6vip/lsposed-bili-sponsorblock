package com.ctf.bilisb.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoamingClient] 的协议回归：签名/传输全注入，单测不触网。
 *
 * 守的协议事实：th 区走 intl 路径且 extra 带 appkey/mobi_app/platform/build；
 * 其余区走 pgc 路径只带 area+access_key；探活看 `"code":0`；逐台降级、全失败带回错误。
 */
class RoamingClientTest {

    private val query = RoamingClient.PlayQuery(
        epId = 285145,
        cid = 120453316,
        qn = 32,
        fnver = 0,
        fnval = 17364,
        forceHost = 2,
        fourk = true,
    )

    @Test
    fun `查询串七参数顺序与取值正确`() {
        assertEquals(
            "ep_id=285145&cid=120453316&qn=32&fnver=0&fnval=17364&force_host=2&fourk=1",
            RoamingClient.buildQuery(query),
        )
        assertEquals(
            "ep_id=0&cid=0&qn=0&fnver=0&fnval=0&force_host=0&fourk=0",
            RoamingClient.buildQuery(query.copy(epId = 0, cid = 0, qn = 0, fnver = 0, fnval = 0, forceHost = 0, fourk = false)),
        )
    }

    @Test
    fun `普通区路径与 extra 参数正确`() {
        val signedQueries = mutableListOf<Pair<String, Map<String, String>>>()
        val client = RoamingClient(
            sign = { q, extra -> signedQueries += q to extra; "$q&signed=1" },
            fetch = { _, _ -> """{"code":0,"quality":80}""" },
        )
        val result = client.fetchPlayUrl(
            listOf(RoamingClient.RoamingServer("cn", "https://roamer.example", "ak-cn")),
            query,
        )

        assertTrue(result.isSuccess)
        assertEquals("cn", result.areaUsed)
        assertEquals(1, signedQueries.size)
        val (q, extra) = signedQueries.single()
        assertTrue(q.startsWith("ep_id=285145&cid=120453316"))
        assertEquals("cn", extra["area"])
        assertEquals("ak-cn", extra["access_key"])
        assertEquals(null, extra["appkey"]) // 非 th 区不带 th appkey
    }

    @Test
    fun `th 区走 intl 路径且 extra 带客户端身份`() {
        val captured = mutableListOf<Pair<String, String>>() // url
        val client = RoamingClient(
            // 模拟真实签名行为：把 extra 参数合并进查询串
            sign = { q, extra -> q + extra.entries.joinToString("") { "&${it.key}=${it.value}" } },
            fetch = { url, mobiApp -> captured += url to mobiApp; """{"code":0}""" },
        )
        val result = client.fetchPlayUrl(
            listOf(RoamingClient.RoamingServer("th", "https://th-roamer.example", "ak-th")),
            query,
        )

        assertTrue(result.isSuccess)
        val (url, mobiApp) = captured.single()
        assertTrue("th 应走 intl 路径: $url", url.startsWith("https://th-roamer.example/intl/gateway/v2/ogv/playurl?"))
        assertTrue(url.contains("area=th"))
        assertTrue(url.contains("access_key=ak-th"))
        assertTrue(url.contains("appkey=7d089525d3611b1c"))
        assertTrue(url.contains("mobi_app=bstar_a"))
        assertEquals(RoamingClient.TH_MOBI_APP, mobiApp)
    }

    @Test
    fun `首台失败降级到第二台 成功即停`() {
        val client = RoamingClient(
            sign = { q, _ -> q },
            fetch = { url, _ ->
                if (url.startsWith("https://bad.example")) """{"code":-404,"message":"no"}"""
                else """{"code":0,"quality":80}"""
            },
        )
        val result = client.fetchPlayUrl(
            listOf(
                RoamingClient.RoamingServer("tw", "https://bad.example", "ak1"),
                RoamingClient.RoamingServer("cn", "https://good.example", "ak2"),
            ),
            query,
        )

        assertTrue(result.isSuccess)
        assertEquals("cn", result.areaUsed)
        assertEquals("""{"code":0,"quality":80}""", result.content)
        assertTrue("首台错误应按区名记录", result.errors.containsKey("tw"))
        assertTrue(result.errors["tw"]!!.contains("code != 0"))
    }

    @Test
    fun `全失败时 content 为空且逐台错误带回`() {
        val client = RoamingClient(
            sign = { _, _ -> "" },
            fetch = { _, _ -> throw java.net.SocketTimeoutException("timeout") },
        )
        val result = client.fetchPlayUrl(
            listOf(
                RoamingClient.RoamingServer("tw", "https://a.example", "ak1"),
                RoamingClient.RoamingServer("hk", "https://b.example", "ak2"),
            ),
            query,
        )

        assertNull(result.content)
        assertNull(result.areaUsed)
        assertEquals("SocketTimeoutException: timeout", result.errors["tw"])
        assertEquals("SocketTimeoutException: timeout", result.errors["hk"])
    }

    @Test
    fun `priorityArea 把指定区提到最前`() {
        val order = mutableListOf<String>()
        val client = RoamingClient(
            sign = { _, _ -> "" },
            fetch = { url, _ -> order += url; """{"code":-404}""" },
        )
        client.fetchPlayUrl(
            listOf(
                RoamingClient.RoamingServer("tw", "https://tw.example", "ak1"),
                RoamingClient.RoamingServer("hk", "https://hk.example", "ak2"),
            ),
            query,
            priorityArea = "hk",
        )
        assertTrue("hk 应先于 tw 被尝试", order.indexOfFirst { it.contains("hk") } < order.indexOfFirst { it.contains("tw") })
    }
}
