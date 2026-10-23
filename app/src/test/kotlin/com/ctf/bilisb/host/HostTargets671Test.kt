package com.ctf.bilisb.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6.7.1（`com.bilibili.app.in` versionCode 9161100）宿主形态的回归覆盖。
 *
 * 全部断言来自 dexscan 索引 + smali 字节码实证，实证结论记录在
 * `docs/APK_6.7.1_DRIFT.md`。任何一条被改动都必须重新跑一遍 dexscan 核对，
 * 否则宿主会「静默不跳过」（与「服务端拉不到片段」无法区分）。
 */
class HostTargets671Test {

    @Test
    fun coreServiceIsInterfaceA() {
        // 6.7.1：PlayerSeekWidget3#getPlayerCoreService() / v2 PlayerProgressTextWidget /
        // 容器 f#m() / PlayDirectorServiceV3.W7() 全部返回 service.A。
        // A 必须排在首位 —— 同一个 type 匹配器在 D/f0 上仍能兜住旧版本。
        assertEquals("tv.danmaku.biliplayerv2.service.A", HostTargets.CORE_SERVICE_TYPES.first())
        assertTrue("service.A 必须留在候选里", "tv.danmaku.biliplayerv2.service.A" in HostTargets.CORE_SERVICE_TYPES)
        // 旧目标不能被移除（6.5.0/6.6.0 仍靠它们）
        assertTrue("service.D 仍须兼容", "tv.danmaku.biliplayerv2.service.D" in HostTargets.CORE_SERVICE_TYPES)
        assertTrue("service.f0 仍须兼容", "tv.danmaku.biliplayerv2.service.f0" in HostTargets.CORE_SERVICE_TYPES)
    }

    @Test
    fun seekTickDispatcherMovedToB0() {
        // 6.7.1：SeekService 实现类 = service.B0，tick 派发器 = B0#k(II)。
        // 6.6.0 的 D0 降级成观察者接口，D0$c 内部类已不存在。
        assertEquals("tv.danmaku.biliplayerv2.service.B0", HostTargets.SEEK_TICK_DISPATCHER_CLASSES.first())
        assertTrue(
            "6.6.0 的 D0\$c 仍须保留兜底",
            "tv.danmaku.biliplayerv2.service.D0\$c" in HostTargets.SEEK_TICK_DISPATCHER_CLASSES,
        )
        assertEquals("k", HostTargets.SEEK_TICK_RUN_METHODS.first())
        assertTrue("run 仍须保留兜底", "run" in HostTargets.SEEK_TICK_RUN_METHODS)
        assertEquals("tv.danmaku.biliplayerv2.service.B0", HostTargets.SEEK_SERVICE_IMPL_671)
        assertEquals("tv.danmaku.biliplayerv2.service.q0", HostTargets.SEEK_TICK_LISTENER_INTERFACE_671)
    }

    @Test
    fun directorObserverUsesD0WithB0M0() {
        // 6.7.1：观察者接口 = service.D0（a/b/c/e 四个回调，参数都是 Video$e）。
        // 注册/注销 = PlayDirectorServiceV3#B0(D0) / #m0(D0)。
        assertEquals("tv.danmaku.biliplayerv2.service.D0", HostTargets.DIRECTOR_OBSERVER_INTERFACES.first())
        assertEquals("B0", HostTargets.DIRECTOR_ADD_OBSERVER_METHODS.first())
        assertEquals("m0", HostTargets.DIRECTOR_REMOVE_OBSERVER_METHODS.first())
        // 旧版本候选不得被挤掉
        assertTrue("j0/l0/J0 仍须兼容", "j0" in HostTargets.DIRECTOR_ADD_OBSERVER_METHODS)
        assertTrue("z0/S1 仍须兼容", "z0" in HostTargets.DIRECTOR_REMOVE_OBSERVER_METHODS)
        assertTrue("E0 仍须兼容 6.5.0", "tv.danmaku.biliplayerv2.service.E0" in HostTargets.DIRECTOR_OBSERVER_INTERFACES)
    }

    @Test
    fun directorCurrentVideoAccessorIsC() {
        // 6.7.1：PlayDirectorServiceV3#C() 是唯一返回真 Video$e 的访问器（P/D/F 全消失，
        // 该类里 D(Z)Z 是无关布尔方法 —— 所以 DIRECTOR_CURRENT_VIDEO_METHODS 的顺序很重要）。
        assertEquals("C", HostTargets.DIRECTOR_CURRENT_VIDEO_METHODS.first())
        assertEquals("tv.danmaku.biliplayerv2.service.Video\$e", HostTargets.DIRECTOR_CURRENT_VIDEO_CLASSES.first())
        assertTrue("P 仍须兼容 3.20.4", "P" in HostTargets.DIRECTOR_CURRENT_VIDEO_METHODS)
    }

    @Test
    fun progressCallbacksInclude671Names() {
        // 6.7.1：int 形态是 H(II)，long 形态是 l0(JJ)。
        assertEquals("H", HostTargets.PROGRESS_CALLBACK_INT_METHODS.first())
        assertEquals("l0", HostTargets.PROGRESS_CALLBACK_LONG_METHODS.first())
        assertTrue("G 仍须兼容 6.5.0", "G" in HostTargets.PROGRESS_CALLBACK_INT_METHODS)
        assertTrue("J 仍须兼容 6.6.0", "J" in HostTargets.PROGRESS_CALLBACK_INT_METHODS)
        assertTrue("j0/k0 仍须兼容", "j0" in HostTargets.PROGRESS_CALLBACK_LONG_METHODS)
    }

    @Test
    fun playDetailsCarriesAidCidIn671() {
        // 6.7.1：Video$e#D() 改返 KL1.f（P2P 稿件元数据）。
        // 字段语义由宿主 KL1.b 装配 P2PManuscriptInfo 实证：c->setAvid, d->setCid。
        assertEquals("KL1.f", HostTargets.PLAY_DETAILS_CLASS)
        assertEquals("D", HostTargets.PLAY_DETAILS_ACCESSOR)
        assertEquals("c", HostTargets.PLAY_DETAILS_AID_FIELD)
        assertEquals("d", HostTargets.PLAY_DETAILS_CID_FIELD)
        // Video$b 链路不删（3.20.4 / 旧版仍是它）
        assertEquals("tv.danmaku.biliplayerv2.service.Video\$b", HostTargets.VIDEO_DETAILS_CLASS)
        // Video$a 兜底不删
        assertEquals("tv.danmaku.biliplayerv2.service.Video\$a", HostTargets.VIDEO_PARAMS_CLASS)
        assertEquals("a", HostTargets.VIDEO_PARAMS_AID_FIELD)
        assertEquals("b", HostTargets.VIDEO_PARAMS_CID_FIELD)
    }

    @Test
    fun directorServiceReachableFromContainerIn671() {
        // 6.7.1：PlayerSeekWidget3 不再暴露返回 director 的方法，唯一入口是容器 cK1.e#B()
        // 返回 service.y（PlayDirectorServiceV3 implements service.y）。
        // 缺 B 或 service.y 会让 directorFromHost 恒 MISS -> aid/cid 拿不到 -> 片段不拉取。
        assertTrue("B 必须可取 director", "B" in HostTargets.DIRECTOR_GET_SERVICE_METHODS)
        assertTrue(
            "service.y 必须被认作 director 服务类型",
            "tv.danmaku.biliplayerv2.service.y" in HostTargets.DIRECTOR_SERVICE_TYPES,
        )
        assertTrue(
            "PlayDirectorServiceV3 仍须在类名候选里",
            "tv.danmaku.biliplayerimpl.videodirector.PlayDirectorServiceV3" in HostTargets.DIRECTOR_SERVICE_TYPES,
        )
    }

    @Test
    fun gripperAccountClassMovedToF() {
        // 6.7.1：account.d 只剩 SuspendProducer 三件套，不再声明 getAccessKey；
        // 唯一实现 GAccount 的是 account.f。
        assertEquals("com.bilibili.gripper.container.account.f", HostTargets.GRIPPER_ACCOUNT_CLASSES.first())
        assertTrue(
            "account.d 仍须兼容 6.5.0/6.6.0",
            "com.bilibili.gripper.container.account.d" in HostTargets.GRIPPER_ACCOUNT_CLASSES,
        )
    }

    @Test
    fun morePanelLayoutIncludes671Holder() {
        // 6.7.1：item 接口 gemini.ui.i -> gemini.ui.j，holder 接口 gemini.ui.i$b -> gemini.ui.j$b。
        // 三元组必须整组同源（adapter + item + holder 同版本）。
        val first = HostTargets.MORE_PANEL_LAYOUTS.first()
        assertEquals(HostTargets.MORE_PANEL_ADAPTER_CLASS, first.first)
        assertEquals("com.bilibili.app.gemini.ui.j", first.second)
        assertEquals("com.bilibili.app.gemini.ui.j\$b", first.third)
        // 6.5.0/6.6.0 的旧三元组仍须保留
        assertTrue(
            "旧 holder 接口仍须兼容",
            HostTargets.MORE_PANEL_LAYOUTS.any { it.third == "com.bilibili.app.gemini.ui.i\$b" },
        )
    }

    @Test
    fun mossDescriptorHintsIncludeIs1() {
        // 6.7.1：MossInterceptor$e.b 的类型从 xr1.g 换成 Is1.g，服务名 a 从 xr1.k 换成 Is1.k。
        // 缺这一项会让 IP 属地的「评论区限定改写」在 6.7.1 上全部落空（服务名恒 null）。
        assertEquals("Is1.g", HostTargets.MOSS_DESCRIPTOR_G_TYPE_HINTS.first())
        assertEquals("Is1.k", HostTargets.MOSS_DESCRIPTOR_K_TYPE_HINTS.first())
        assertEquals(
            listOf("Is1", "kr1", "Zq1", "jp1", "xr1"),
            HostTargets.MOSS_DESCRIPTOR_G_TYPE_HINTS.map { it.substringBeforeLast('.') },
        )
        assertEquals(
            listOf("Is1", "kr1", "Zq1", "jp1", "xr1"),
            HostTargets.MOSS_DESCRIPTOR_K_TYPE_HINTS.map { it.substringBeforeLast('.') },
        )
    }
}
