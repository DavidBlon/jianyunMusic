package com.ncm.app.plugin.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatModulesTest {

    @Test
    fun registeredCompatModulesAreOnlyKnownOnes() {
        assertTrue("axios" in COMPAT_MODULE_NAMES)
        assertTrue("qs" in COMPAT_MODULE_NAMES)
        assertFalse("fs" in COMPAT_MODULE_NAMES)
        assertFalse("child_process" in COMPAT_MODULE_NAMES)
    }
}
