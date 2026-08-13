package com.ncm.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSourceKeyQuickPasteTest {

    @Test
    fun pastingTheSameKeyCreatesANewImmediateValidationRequest() {
        val result = quickPasteKeyInput(
            clipboardText = "same-key-1234",
            currentValidationRevision = 7
        )

        val pasted = result as QuickPasteKeyInput.Pasted
        assertEquals("same-key-1234", pasted.value)
        assertEquals(8, pasted.validationRevision)
    }

    @Test
    fun emptyClipboardReturnsVisibleGuidance() {
        val result = quickPasteKeyInput(
            clipboardText = "  ",
            currentValidationRevision = 2
        )

        val unavailable = result as QuickPasteKeyInput.Unavailable
        assertTrue(unavailable.message.contains("先复制"))
    }
}
