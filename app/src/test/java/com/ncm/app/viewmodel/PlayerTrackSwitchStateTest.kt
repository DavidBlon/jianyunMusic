package com.ncm.app.viewmodel

import com.ncm.app.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTrackSwitchStateTest {

    @Test
    fun selectingAnotherTrackImmediatelyClearsThePreviousProgress() {
        val nextSong = Song(id = 2L, name = "Next", dt = 205_000L)
        val previous = PlayerUiState(
            currentSong = Song(id = 1L, name = "Previous", dt = 180_000L),
            songUrl = "https://audio.example/previous.mp3",
            lyric = "old lyric",
            isPlaying = true,
            isLoading = false,
            progress = 0.72f,
            currentPosition = 129_600L,
            duration = 180_000L,
            isLiked = true,
            error = "old error"
        )

        val pending = previous.forPendingTrackSwitch(nextSong, "plugin:linglan.wy")

        assertEquals(nextSong, pending.currentSong)
        assertEquals("plugin:linglan.wy", pending.audioSource)
        assertEquals(205_000L, pending.duration)
        assertEquals(0L, pending.currentPosition)
        assertEquals(0f, pending.progress)
        assertFalse(pending.isPlaying)
        assertTrue(pending.isLoading)
        assertNull(pending.songUrl)
        assertNull(pending.lyric)
        assertNull(pending.error)
    }
}
