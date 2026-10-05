package com.ctf.bilisb.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [RoamingUnlockCore] 纯 JVM 回归（不触网、不碰宿主）。
 *
 * 守的核心事实是「事实 → 七参数查询串」这条映射：它有两份实现（核心的纯函数，
 * 以及网络真正走的 [RoamingClient.buildQuery]），**逐字相等必须被钉住**——
 * 少一个 `&`、换一个参数名或调一次顺序，服务器就会按缺参解析（表现为莫名其妙的 -400），
 * 而这种漂移在真机上只会以「解锁失效」的形式出现，很难回溯到查询串。
 *
 * 边界特意覆盖 epId=0（重定向/下载取地址场景请求侧不带 ep）与 fourk 两种取值
 * （fourk 是唯一映射成 1/0 的布尔参数，写反了不会报错，只会少一路清晰度）。
 */
class RoamingUnlockCoreTest {

    private val facts = RoamingUnlockCore.UnlockRequestFacts(
        epId = 285145,
        cid = 120453316,
        seasonId = "33072",
        qn = 32,
        fnver = 0,
        fnval = 17364,
        forceHost = 2,
        fourk = true,
        isDownload = false,
    )

    @Test
    fun `事实查询串与 RoamingClientbuildQuery 逐字一致`() {
        assertEquals(
            "ep_id=285145&cid=120453316&qn=32&fnver=0&fnval=17364&force_host=2&fourk=1",
            RoamingUnlockCore.buildQuery(facts),
        )
        // 两份实现（核心纯函数 / 网络实际使用的 RoamingClient.buildQuery）必须逐字相同
        assertEquals(
            RoamingClient.buildQuery(RoamingUnlockCore.playQuery(facts)),
            RoamingUnlockCore.buildQuery(facts),
        )
    }

    @Test
    fun `epId 为 0 与 fourk 两个取值都与 RoamingClient 一致`() {
        val cases = listOf(
            facts.copy(epId = 0, cid = 0),
            facts.copy(epId = 0, fourk = false),
            facts.copy(fourk = false),
            facts.copy(epId = 0, cid = 0, qn = 0, fnver = 0, fnval = 0, forceHost = 0, fourk = false),
        )
        for (case in cases) {
            assertEquals(
                "查询串与 RoamingClient 不一致: $case",
                RoamingClient.buildQuery(RoamingUnlockCore.playQuery(case)),
                RoamingUnlockCore.buildQuery(case),
            )
        }
        assertEquals(
            "fourk=false 必须是 0（写反不会报错，只会少一路清晰度）",
            "ep_id=0&cid=0&qn=0&fnver=0&fnval=0&force_host=0&fourk=0",
            RoamingUnlockCore.buildQuery(facts.copy(epId = 0, cid = 0, qn = 0, fnver = 0, fnval = 0, forceHost = 0, fourk = false)),
        )
        assertEquals(
            "ep_id=285145&cid=120453316&qn=32&fnver=0&fnval=17364&force_host=2&fourk=0",
            RoamingUnlockCore.buildQuery(facts.copy(fourk = false)),
        )
    }

    @Test
    fun `season 与 download 不进查询串`() {
        val query = RoamingUnlockCore.buildQuery(facts)
        assertFalse("season_id 不是播放地址参数，出现在查询串里说明映射写错了: $query", query.contains("season"))
        assertFalse("download 不是播放地址参数，出现在查询串里说明映射写错了: $query", query.contains("download"))
        // 这两个字段只影响受限判定与异常兜底，改了它们查询串必须原地不动
        assertEquals(query, RoamingUnlockCore.buildQuery(facts.copy(seasonId = "9", isDownload = true)))
    }
}
