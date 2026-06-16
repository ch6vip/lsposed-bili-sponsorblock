package com.ctf.bilisb.util

import java.security.MessageDigest

object HashUtils {
    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun hashPrefix(value: String): String {
        return sha256Hex(value).take(4)
    }
}

