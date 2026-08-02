package com.ncm.app.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class AppPlayerAudioFocusConfigTest {

    @Test
    fun musicPlaybackUsesMediaAudioAttributes() {
        assertEquals(C.USAGE_MEDIA, AppPlayer.musicAudioAttributes.usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, AppPlayer.musicAudioAttributes.contentType)
    }
}
