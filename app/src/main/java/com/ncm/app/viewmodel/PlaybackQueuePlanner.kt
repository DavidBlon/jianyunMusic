package com.ncm.app.viewmodel

import com.ncm.app.data.model.Song

internal object PlaybackQueuePlanner {

    fun windowAfter(
        currentSongId: Long,
        playQueue: List<Song>,
        currentIndex: Int,
        windowSize: Int
    ): List<Song> {
        if (playQueue.isEmpty() || windowSize <= 0) return emptyList()

        val startIndex = playQueue.indexOfFirst { it.id == currentSongId }
            .takeIf { it >= 0 }
            ?: currentIndex.coerceIn(0, playQueue.lastIndex)

        return (1..windowSize)
            .mapNotNull { offset -> playQueue.getOrNull((startIndex + offset) % playQueue.size) }
            .filter { it.id != currentSongId }
            .distinctBy { it.id }
    }
}
