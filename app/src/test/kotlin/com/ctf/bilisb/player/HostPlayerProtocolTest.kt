package com.ctf.bilisb.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Test
import tv.danmaku.biliplayerv2.service.Video

class HostPlayerProtocolTest {
    interface Core
    class CoreImpl : Core
    class Container(val core: CoreImpl) {
        var unrelatedCalled = false
        fun q(): String {
            unrelatedCalled = true
            error("q is unrelated in this host version")
        }
        fun getPlayerCoreService(): CoreImpl = core
    }
    open class Parent(core: CoreImpl) {
        @Suppress("unused")
        private val hiddenCore: CoreImpl = core
    }
    class Child(core: CoreImpl) : Parent(core)
    class Director(private val video: Video.f) {
        var wrongAccessorCalled = false
        fun D(): String {
            wrongAccessorCalled = true
            error("D does not return a video in this version")
        }
        fun P(): Video.f = video
    }

    @Test
    fun unrelatedShortMethodIsRejectedBeforeInvocationAndConcreteSubtypeIsAccepted() {
        val core = CoreImpl()
        val host = Container(core)
        assertSame(core, HostPlayerProtocol.invokeTyped(
            host, listOf("q", "getPlayerCoreService"), listOf(Core::class.java.name),
        ))
        assertFalse(host.unrelatedCalled)
    }

    @Test
    fun privateInheritedCoreFieldCanBeReadByInterfaceType() {
        val core = CoreImpl()
        assertSame(core, HostPlayerProtocol.readTypedField(Child(core), listOf(Core::class.java.name)))
    }

    @Test
    fun newVideoUsesDanmakuDetailsAndDoesNotTouchInteractionMetadata() {
        val video = Video.f(Video.b(98_935_548L, 168_885_122L))
        val director = Director(video)
        assertSame(video, HostPlayerProtocol.currentVideo(director))
        assertFalse(director.wrongAccessorCalled)
        assertEquals(98_935_548L to 168_885_122L, HostPlayerProtocol.knownVideoIds(video))
    }

    @Test
    fun previousVideoParamsPathStillWorksIncludingOldSmallIds() {
        val video = Video.e(Video.a(170001L, 279786L))
        assertEquals(170001L to 279786L, HostPlayerProtocol.knownVideoIds(video))
    }

    @Test
    fun missingOrInvalidDetailsFailClosed() {
        assertNull(HostPlayerProtocol.knownVideoIds(Video.f(null)))
        assertNull(HostPlayerProtocol.knownVideoIds(Video.f(Video.b(1L, 0L))))
        assertNull(HostPlayerProtocol.knownVideoIds(Any()))
    }
}
