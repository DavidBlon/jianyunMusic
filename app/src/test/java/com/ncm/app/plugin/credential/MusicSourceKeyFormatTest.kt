package com.ncm.app.plugin.credential

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSourceKeyFormatTest {

    @Test
    fun acceptsHeaderSafeKeysAndRejectsNaturalLanguageOrControls() {
        assertTrue(isValidMusicSourceKey("abcDEF-_+/=123456"))
        assertFalse(isValidMusicSourceKey("网易云音源没有封面，请修复一下"))
        assertFalse(isValidMusicSourceKey("short"))
        assertFalse(isValidMusicSourceKey("valid-looking-key\nInjected: value"))
    }
}
