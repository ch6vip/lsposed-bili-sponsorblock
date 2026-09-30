package com.ctf.bilisb.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「B 站增强」候选表（[HostTargets] 增强段）的防回归。
 *
 * 存在的理由：候选**顺序就是行为**（解析按顺序取第一个命中，mq0.a 在 oq0.a 前 = 6.3.0 优先），
 * 而收编时有机器改写过的痕迹要钉住；更重要的是用源码扫描钉死「宿主类名只进这一张表」——
 * 增强四件套曾经把 hook 点散落在各自文件里，宿主改版要逐文件翻（项目技术债「高」级第一条）。
 *
 * SDK/平台/协议类名（okhttp3、android.app、com.tencent.tauth、com.bapis.*）不混淆、不漂移，
 * 刻意留在 hook 文件里，不进表 —— 扫描白名单按此口径。
 */
class EnhanceTargetsTest {

    // ------------------------------------------------------------ 候选顺序即行为

    @Test
    fun `身份 provider 候选保持版本顺序`() {
        // 6.3.0=mq0.a 在前，6.4.0+=oq0.a 在后；顺序颠倒会让 6.3.0 宿主挂错版本
        assertEquals(listOf("mq0.a", "oq0.a"), HostTargets.IDENTITY_PROVIDER_CLASSES)
        assertEquals(listOf("e", "d"), HostTargets.IDENTITY_PROVIDER_METHODS)
    }

    @Test
    fun `REST 拦截器候选保持版本顺序`() {
        assertEquals(listOf("Aq0.a", "Cq0.a"), HostTargets.REST_INTERCEPTOR_CLASSES)
    }

    @Test
    fun `空间页 Activity 候选 Local 在前`() {
        // 6.4.0+ 用户实际打开的是 LocalAuthorSpaceActivity，必须先试
        assertEquals(
            "com.bilibili.app.authorspace.local.LocalAuthorSpaceActivity",
            HostTargets.SPACE_UI_ACTIVITY_CLASSES.first(),
        )
    }

    @Test
    fun `KMP 头提供者候选顺序不变`() {
        assertEquals(listOf("kr1.a", "up1.a"), HostTargets.KMP_HEADER_PROVIDER_CLASSES)
    }

    @Test
    fun `moss 描述符类型提示包含全部已知版本`() {
        // 6.5.0=kr1 / 6.4.0=Zq1 / 6.3.0=jp1 / 6.6.0=xr1 —— 缺一个丢一个版本的定位能力
        listOf(HostTargets.MOSS_DESCRIPTOR_G_TYPE_HINTS, HostTargets.MOSS_DESCRIPTOR_K_TYPE_HINTS)
            .forEach { hints ->
                assertEquals(
                    listOf("kr1", "Zq1", "jp1", "xr1"),
                    hints.map { it.substringBeforeLast('.') },
                )
            }
    }

    @Test
    fun `真名类常量不被手滑改动`() {
        assertEquals(
            "com.bilibili.app.gemini.player.widget.like.VideoTripleLike",
            HostTargets.TRIPLE_LIKE_CLASS,
        )
        assertEquals(
            "com.bilibili.playerbizcommonv2.widget.popup.FollowPopupUtil",
            HostTargets.FOLLOW_POPUP_CLASS,
        )
        assertEquals(
            "com.bilibili.playerbizcommonv2.danmaku.command.InteractDanmakuListWidget",
            HostTargets.VOTE_WIDGET_CLASS,
        )
        assertEquals(
            "com.bilibili.pegasus.vm.PegasusViewModel",
            HostTargets.PEGASUS_VM_CLASS,
        )
        assertEquals(
            "kntr.base.moss.ignet.impl.header.b",
            HostTargets.MOSS_COMMON_HEADERS_CLASS,
        )
        assertEquals(
            "kntr.base.moss.ignet.impl.grpc.c",
            HostTargets.MOSS_GRPC_BIN_WRITE_CLASS,
        )
    }

    // ------------------------------------------------------------ 一张表口径（源码扫描）

    /** 允许留在 hook 文件里的 Class.forName 字面量前缀（SDK / 平台 / protobuf wire 类型）。 */
    private val allowedLiteralPrefixes = listOf(
        "okhttp3.", "android.app.", "com.tencent.tauth.", "com.bapis.",
    )

    private fun hookSources(): Map<String, List<String>> =
        listOf(
            "IpLocationHooks.kt",
            "InteractHintHooks.kt",
            "HomeNoAutoRefreshHooks.kt",
            "ShareQqHooks.kt",
        ).associateWith {
            File("src/main/kotlin/com/ctf/bilisb/hook/$it").readLines()
        }

    @Test
    fun `增强 hook 文件不再散落宿主类名字面量`() {
        val offenders = mutableListOf<String>()
        hookSources().forEach { (name, lines) ->
            lines.withIndex().forEach { (i, raw) ->
                val line = raw.substringBefore("//")
                Regex("Class\\.forName\\(\\s*\"([^\"]+)\"").findAll(line).forEach { m ->
                    val cls = m.groupValues[1]
                    if (allowedLiteralPrefixes.none { cls.startsWith(it) }) {
                        offenders += "$name:${i + 1}: \"$cls\"（宿主类名应进 HostTargets）"
                    }
                }
            }
        }
        assertEquals(
            "增强 hook 的宿主候选必须集中在 HostTargets（宿主改版只改一张表）。发现：\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `hook 文件确实引用了 HostTargets 候选`() {
        // 防「扫过了但引用被删」的假通过：每个文件至少引用一次
        hookSources().forEach { (name, lines) ->
            val joined = lines.joinToString("\n")
            assertTrue("$name 应引用 HostTargets", "HostTargets." in joined)
        }
    }
}
