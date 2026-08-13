package com.ncm.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionGenerationTest {

    private fun newManager(): SessionManager = SessionManager(RuntimeEnvironment.getApplication())

    @Test
    fun initialGenerationIsZero() {
        assertEquals(0, newManager().sessionGeneration)
    }

    @Test
    fun invalidateIncrementsGeneration() {
        val manager = newManager()
        manager.invalidate()
        assertEquals(1, manager.sessionGeneration)
        manager.invalidate()
        assertEquals(2, manager.sessionGeneration)
    }
}
