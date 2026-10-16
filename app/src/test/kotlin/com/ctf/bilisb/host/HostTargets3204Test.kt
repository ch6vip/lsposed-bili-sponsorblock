package com.ctf.bilisb.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the 3.20.4 host shape. */
class HostTargets3204Test {
    @Test
    fun playerBindingAndCoreCandidatesArePresent() {
        assertTrue("q(h) binding must be available", HostTargets.BIND_PLAYER_SERVICE_METHOD in listOf("q"))
        assertTrue("h context accessor must be first", HostTargets.CONTAINER_CONTEXT_METHODS.first() == "z")
        assertTrue("service.f0 core must be supported", "tv.danmaku.biliplayerv2.service.f0" in HostTargets.CORE_SERVICE_TYPES)
        assertTrue("3.20.4 progress callback must be supported", "U" in HostTargets.PROGRESS_CALLBACK_INT_METHODS)
    }

    @Test
    fun directorUsesU2AndJ0S1() {
        assertTrue("u2 observer must be supported", "tv.danmaku.biliplayerv2.service.u2" in HostTargets.DIRECTOR_OBSERVER_INTERFACES)
        assertTrue("J0 must be an add candidate", "J0" in HostTargets.DIRECTOR_ADD_OBSERVER_METHODS)
        assertTrue("S1 must be a remove candidate", "S1" in HostTargets.DIRECTOR_REMOVE_OBSERVER_METHODS)
        assertEquals("P", HostTargets.DIRECTOR_CURRENT_VIDEO_METHODS.first())
    }

    @Test
    fun metadataPathIsVideoFDToVideoB() {
        assertEquals("D", HostTargets.VIDEO_DETAILS_ACCESSOR)
        assertEquals("tv.danmaku.biliplayerv2.service.Video\$b", HostTargets.VIDEO_DETAILS_CLASS)
        assertEquals("tv.danmaku.biliplayerv2.service.c0", HostTargets.DIRECTOR_SERVICE_TYPES.last())
    }
}
