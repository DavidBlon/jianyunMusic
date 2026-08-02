package com.ncm.app

import com.ncm.app.ui.navigation.KeyboardDismissalTarget
import com.ncm.app.ui.navigation.Routes
import com.ncm.app.ui.navigation.shouldDismissKeyboardForTransition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardDismissalPolicyTest {

    @Test
    fun `switching routes dismisses a focused keyboard`() {
        assertTrue(
            shouldDismissKeyboardForTransition(
                previous = KeyboardDismissalTarget(Routes.SEARCH, null),
                current = KeyboardDismissalTarget(Routes.MY, null)
            )
        )
    }

    @Test
    fun `opening the player overlay dismisses a focused keyboard without a route change`() {
        assertTrue(
            shouldDismissKeyboardForTransition(
                previous = KeyboardDismissalTarget(Routes.SEARCH, null),
                current = KeyboardDismissalTarget(Routes.SEARCH, 10086L)
            )
        )
    }

    @Test
    fun `initial screen and unchanged screen do not create redundant dismissal events`() {
        val search = KeyboardDismissalTarget(Routes.SEARCH, null)

        assertFalse(shouldDismissKeyboardForTransition(previous = null, current = search))
        assertFalse(shouldDismissKeyboardForTransition(previous = search, current = search))
    }
}
