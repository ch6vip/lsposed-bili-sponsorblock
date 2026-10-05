package com.ctf.bilisb.unlock

import com.ctf.bilisb.unlock.proto.PlayViewUniteReq
import org.junit.Assert.*
import org.junit.Test

class CacheRequestPatchTest {
    private val fixture = javaClass.classLoader!!.getResourceAsStream("unlock/req_sample.bin")!!.readBytes()

    @Test fun `normal playback remains byte identical`() {
        val bytes = PlayViewUniteReq.parseFrom(fixture).toBuilder()
            .setVod(PlayViewUniteReq.parseFrom(fixture).vod.toBuilder().setDownload(0)).build().toByteArray()
        assertSame(bytes, CacheRequestPatch.patch(bytes))
    }

    @Test fun `downloads preserve new host capabilities and unrelated fields`() {
        val original = PlayViewUniteReq.parseFrom(fixture)
        val request = original.toBuilder().setVod(original.vod.toBuilder()
            .setDownload(1).setFnval(16384).setFourk(false)).build()
        val result = PlayViewUniteReq.parseFrom(CacheRequestPatch.patch(request.toByteArray()))
        assertEquals(0, result.vod.download)
        assertEquals(16384 or 4048, result.vod.fnval)
        assertTrue(result.vod.fourk)
        assertEquals(request.extraContentMap, result.extraContentMap)
        assertEquals(request.bvid, result.bvid)
        // Restore touched values: includes preservation of every unknown field from the live fixture.
        assertEquals(request, result.toBuilder().setVod(result.vod.toBuilder()
            .setDownload(1).setFnval(16384).setFourk(false)).build())
    }

    @Test fun `serialization failure preserves request identity`() {
        val request = Any()
        var failed = false
        assertSame(request, CacheRequestPatch.apply(request,
            { error("serialize failed") }, { error("must not parse") }, { failed = true }))
        assertTrue(failed)
    }

    @Test fun `host parse failure preserves request identity`() {
        val request = Any()
        val parsed = PlayViewUniteReq.parseFrom(fixture)
        val bytes = parsed.toBuilder().setVod(parsed.vod.toBuilder().setDownload(1)).build().toByteArray()
        var failed = false
        assertSame(request, CacheRequestPatch.apply(request,
            { bytes }, { error("host rejects new bytes") }, { failed = true }))
        assertTrue(failed)
    }
}
