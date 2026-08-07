package com.ncm.app.plugin.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SsrfGuardTest {

    @Test
    fun allowsPublicHttps() {
        val guard = SsrfGuard()
        assertTrue(guard.validate("https://music.163.com/api/").isAllow)
    }

    @Test
    fun deniesNonHttpProtocols() {
        val guard = SsrfGuard()
        val denials = listOf(
            "file:///etc/passwd",
            "content://media/audio",
            "intent://x",
            "http://127.0.0.1/admin",
            "http://10.0.0.1/admin",
            "http://192.168.1.1/admin",
            "http://[::1]/admin"
        )
        denials.forEach { assertTrue("$it must be denied", guard.validate(it).isDeny) }
    }

    @Test
    fun deniesPrivateHttpsLiterals() {
        val guard = SsrfGuard()
        val denials = listOf(
            "https://127.0.0.1/admin",
            "https://10.0.0.1/admin",
            "https://192.168.1.1/admin",
            "https://172.16.0.1/admin",
            "https://[::1]/admin",
            "https://localhost/admin"
        )
        denials.forEach { assertTrue("$it must be denied", guard.validate(it).isDeny) }
    }

    @Test
    fun deniesRestrictedPorts() {
        val guard = SsrfGuard(restrictedPorts = setOf(22, 3306, 6379))
        assertTrue(guard.validate("https://example.com:22/x").isDeny)
        assertTrue(guard.validate("https://example.com:3306/x").isDeny)
        assertTrue(guard.validate("https://example.com:443/x").isAllow)
    }

    @Test
    fun resolvedPrivateAddressIsDenied() {
        val guard = SsrfGuard()
        assertTrue(guard.validateResolved(java.net.InetAddress.getByName("127.0.0.1"), 443).isDeny)
        assertTrue(guard.validateResolved(java.net.InetAddress.getByName("192.168.1.1"), 443).isDeny)
        assertTrue(guard.validateResolved(java.net.InetAddress.getByName("8.8.8.8"), 443).isAllow)
    }
}
