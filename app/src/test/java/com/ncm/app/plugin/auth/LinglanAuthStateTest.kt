package com.ncm.app.plugin.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class LinglanAuthStateTest {

    @Test
    fun serverAuthFailureBecomesExpiredOrRevokedNotError() {
        assertEquals(LinglanAuthState.EXPIRED, nextStateForServerResponse(LinglanAuthState.ACTIVE, 401, 401))
        assertEquals(LinglanAuthState.REVOKED, nextStateForServerResponse(LinglanAuthState.ACTIVE, 403, 403))
    }

    @Test
    fun networkFailureIsNotKeyInvalid() {
        assertEquals(LinglanAuthState.ERROR, nextStateForServerResponse(LinglanAuthState.ACTIVE, 0, null))
    }

    @Test
    fun successBecomesActive() {
        assertEquals(LinglanAuthState.ACTIVE, nextStateForServerResponse(LinglanAuthState.VALIDATING, 200, 200))
    }

    @Test
    fun rateLimitIsErrorNotExpired() {
        assertEquals(LinglanAuthState.ERROR, nextStateForServerResponse(LinglanAuthState.ACTIVE, 429, null))
    }

    @Test
    fun staleOfflineAfterTwentyFourHoursWithoutValidation() {
        val now = 1_000_000_000L
        val info = LinglanAuthInfo(
            validUntilEpochMs = now + 86_400_000L,
            lastVerifiedAtEpochMs = now - 86_400_001L,
            capability = emptySet()
        )
        assertEquals(true, shouldRevalidate(info, now))
    }

    @Test
    fun freshValidationDoesNotNeedRevalidation() {
        val now = 1_000_000_000L
        val info = LinglanAuthInfo(
            validUntilEpochMs = now + 86_400_000L,
            lastVerifiedAtEpochMs = now - 3_600_000L,
            capability = emptySet()
        )
        assertEquals(false, shouldRevalidate(info, now))
    }
}
