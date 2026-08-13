package com.ncm.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstUseMusicSourcePromptPolicyTest {

    @Test
    fun firstUseWithoutAKeyShowsPromptUntilUserDismissesOrCompletesIt() {
        assertTrue(
            shouldShowFirstUseMusicSourcePrompt(
                promptCompleted = false,
                hasStoredKey = false,
                hasSelectedSource = false,
                dismissedThisSession = false
            )
        )
        assertTrue(shouldShowFirstUseMusicSourcePrompt(false, true, false, false))
        assertTrue(shouldShowFirstUseMusicSourcePrompt(true, true, false, false))
        assertFalse(shouldShowFirstUseMusicSourcePrompt(false, true, true, false))
        assertFalse(shouldShowFirstUseMusicSourcePrompt(true, false, false, false))
        assertFalse(shouldShowFirstUseMusicSourcePrompt(false, false, false, true))
    }
}
