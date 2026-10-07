package com.ctf.bilisb.unlock

import org.junit.Assert.*
import org.junit.Test

class SearchRequestPolicyTest {
    @Test fun `native movie and bangumi are never intercepted in any configuration`() {
        for (type in listOf(7, 8, 2)) for (enabled in listOf(false, true))
            for (search in listOf(false, true)) for (server in listOf(false, true)) {
                assertEquals(SearchRequestPolicy.Route.ORIGINAL,
                    SearchRequestPolicy.route(type, enabled, search, server, "tw"))
            }
    }

    @Test fun `disabled or unconfigured regional page falls back to native bangumi`() {
        for (flags in listOf(listOf(false, true, true), listOf(true, false, true), listOf(true, true, false))) {
            assertEquals(SearchRequestPolicy.Route.NATIVE_BANGUMI,
                SearchRequestPolicy.route(810, flags[0], flags[1], flags[2], "tw"))
        }
        assertEquals(SearchRequestPolicy.Route.NATIVE_BANGUMI,
            SearchRequestPolicy.route(810, true, true, true, "cn"))
    }

    @Test fun `only marked regional request reaches server`() {
        assertEquals(SearchRequestPolicy.Route.REGIONAL,
            SearchRequestPolicy.route(810, true, true, true, "tw"))
        assertEquals(SearchRequestPolicy.Route.REGIONAL,
            SearchRequestPolicy.route(810, true, true, true, "hk"))
        assertEquals(SearchRequestPolicy.Route.REGIONAL,
            SearchRequestPolicy.route(SearchRequestPolicy.MARKER_TYPE_TH, true, true, true, "th"))
        assertEquals(SearchRequestPolicy.Route.NATIVE_BANGUMI,
            SearchRequestPolicy.route(SearchRequestPolicy.MARKER_TYPE_TH, true, true, true, "cn"))
    }

    @Test fun `next page cursor and requested size are retained`() {
        assertEquals(SearchRequestPolicy.Page(1, 20), SearchRequestPolicy.page("", 0))
        assertEquals(SearchRequestPolicy.Page(2, 30), SearchRequestPolicy.page("2", 30))
        assertEquals(SearchRequestPolicy.Page(3, 100), SearchRequestPolicy.page("3", 10000))
    }

    @Test fun `invalid cursor does not silently replay first page`() {
        for (cursor in listOf("invalid", "0", "-1", "2147483647")) {
            assertTrue(runCatching { SearchRequestPolicy.page(cursor, 20) }.isFailure)
        }
    }

    @Test fun `response sends next and previous cursors and stops at last page`() {
        val body = """{"code":0,"data":{"pages":3,"items":[]}}"""
        fun cursor(page: Int): Map<Int, String> {
            val response = SearchUnlockHook.buildSearchResponseBytes("x", body, SearchRequestPolicy.Page(page, 20))
            assertEquals(page.toLong(), WireSplice.parse(response).first { it.field == 10 }.varint())
            return WireSplice.parse(WireSplice.firstMessage(response, 7)!!)
                .associate { it.field to it.payload().decodeToString() }
        }
        assertEquals(mapOf(1 to "2"), cursor(1))
        assertEquals(mapOf(1 to "3", 2 to "1"), cursor(2))
        assertEquals(mapOf(2 to "2"), cursor(3))
    }
}
