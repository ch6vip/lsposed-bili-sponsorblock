package com.ctf.bilisb.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextPolicyHooksTest {
    @Test
    fun permitsOnlyConfiguredHttpHost() {
        assertTrue(CleartextPolicyHooks.hostMatches("http://10.0.2.2:8080/", "10.0.2.2"))
        assertTrue(CleartextPolicyHooks.hostMatches("http://10.0.2.2:8080/", "10.0.2.2".uppercase()))
        assertFalse(CleartextPolicyHooks.hostMatches("http://10.0.2.2:8080/", "evil.com"))
        assertFalse(CleartextPolicyHooks.hostMatches("http://10.0.2.2:8080/", null))
        assertFalse(CleartextPolicyHooks.hostMatches("http://10.0.2.2:8080/", ""))
    }

    @Test
    fun rejectsHttpsAndInvalidAddresses() {
        assertFalse(CleartextPolicyHooks.hostMatches("https://bsbsb.top", "bsbsb.top"))
        assertFalse(CleartextPolicyHooks.hostMatches("ftp://10.0.2.2", "10.0.2.2"))
        assertFalse(CleartextPolicyHooks.hostMatches("http://", "localhost"))
        assertFalse(CleartextPolicyHooks.hostMatches("https://bsbsb.top@evil.com", "evil.com"))
    }
}
