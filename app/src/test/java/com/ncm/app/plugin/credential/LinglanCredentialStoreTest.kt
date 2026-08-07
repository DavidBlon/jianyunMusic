package com.ncm.app.plugin.credential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinglanCredentialStoreTest {

    private class TestVault : SecretVault {
        private var value: String? = null
        override fun write(value: String): Boolean { this.value = value; return true }
        override fun read(): String? = value
        override fun wipe() { value = null }
    }

    @Test
    fun saveThenReadRoundTrips() {
        val store = LinglanCredentialStore(TestVault())
        assertTrue(store.save("linglan-secret-1234"))
        assertEquals("linglan-secret-1234", store.read())
        assertEquals("••••1234", store.masked())
        assertTrue(store.hasCredential())
    }

    @Test
    fun shortSecretIsRejected() {
        val store = LinglanCredentialStore(TestVault())
        assertFalse(store.save("abc"))
        assertNull(store.read())
    }

    @Test
    fun clearWipesSecretAndMask() {
        val store = LinglanCredentialStore(TestVault())
        store.save("secret-abc")
        store.clear()
        assertNull(store.read())
        assertNull(store.masked())
        assertFalse(store.hasCredential())
    }
}
