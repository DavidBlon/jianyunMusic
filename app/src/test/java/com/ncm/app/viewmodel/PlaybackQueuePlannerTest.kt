package com.ncm.app.viewmodel

import com.ncm.app.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueuePlannerTest {

    @Test
    fun windowAfter_returnsEmptyForEmptyQueue() {
        val result = PlaybackQueuePlanner.windowAfter(
            currentSongId = 1,
            playQueue = emptyList(),
            currentIndex = 0,
            windowSize = 3
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun windowAfter_wrapsQueueAndSkipsCurrentSong() {
        val queue = listOf(song(1), song(2), song(3))

        val result = PlaybackQueuePlanner.windowAfter(
            currentSongId = 3,
            playQueue = queue,
            currentIndex = 2,
            windowSize = 3
        )

        assertEquals(listOf(1L, 2L), result.map { it.id })
    }

    @Test
    fun windowAfter_usesCurrentIndexWhenCurrentSongIsNotInQueue() {
        val queue = listOf(song(10), song(20), song(30))

        val result = PlaybackQueuePlanner.windowAfter(
            currentSongId = 99,
            playQueue = queue,
            currentIndex = 1,
            windowSize = 2
        )

        assertEquals(listOf(30L, 10L), result.map { it.id })
    }

    private fun song(id: Long) = Song(id = id, name = "song-$id")
}
