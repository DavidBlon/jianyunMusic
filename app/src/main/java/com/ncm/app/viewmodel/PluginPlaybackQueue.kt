package com.ncm.app.viewmodel

import com.ncm.app.plugin.model.OnlineTrack

internal class PluginPlaybackQueue {
    var tracks: List<OnlineTrack> = emptyList()
        private set

    var selectedIndex: Int = 0
        private set

    fun set(items: List<OnlineTrack>, startIndex: Int = 0) {
        tracks = items.distinctBy { it.key }
        selectedIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
    }

    fun current(): OnlineTrack? = tracks.getOrNull(selectedIndex)

    fun select(track: OnlineTrack): Boolean {
        val index = tracks.indexOfFirst { it.key == track.key }
        if (index < 0) return false
        selectedIndex = index
        return true
    }

    fun trackForSongId(songId: Long): OnlineTrack? =
        tracks.firstOrNull { pluginShellSongId(it.key) == songId }

    fun next(): OnlineTrack? {
        if (tracks.isEmpty()) return null
        selectedIndex = (selectedIndex + 1) % tracks.size
        return current()
    }

    fun previous(): OnlineTrack? {
        if (tracks.isEmpty()) return null
        selectedIndex = if (selectedIndex == 0) tracks.lastIndex else selectedIndex - 1
        return current()
    }

    fun replaceSelected(requested: OnlineTrack, resolved: OnlineTrack) {
        if (current()?.key != requested.key) return
        tracks = tracks.toMutableList().apply { this[selectedIndex] = resolved }
    }
}
