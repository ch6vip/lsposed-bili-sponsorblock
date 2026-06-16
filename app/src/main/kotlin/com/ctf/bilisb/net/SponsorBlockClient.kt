package com.ctf.bilisb.net

import com.ctf.bilisb.model.SponsorSegment

class SponsorBlockClient(
    private val baseUrl: String,
) {
    fun endpointForHashPrefix(hashPrefix: String): String {
        return "${baseUrl.trimEnd('/')}/api/skipSegments/$hashPrefix"
    }

    fun parseSegments(raw: String): List<SponsorSegment> {
        return emptyList()
    }
}

