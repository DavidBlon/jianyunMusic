package com.ncm.app.viewmodel

import com.ncm.app.data.FavoriteOrderStore
import com.ncm.app.data.model.Song
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey

internal fun pluginShellSongId(key: ProviderTrackKey): Long =
    -((key.asComposite().hashCode().toLong() and 0xffff_ffffL) + 1L)

/** One playback order shared by the playlist UI and all local/online audio sources. */
internal sealed interface PlaylistPlaybackEntry {
    val stableKey: String

    data class Local(val song: Song) : PlaylistPlaybackEntry {
        override val stableKey: String = FavoriteOrderStore.localKey(song.id)
    }

    data class Online(val track: OnlineTrack) : PlaylistPlaybackEntry {
        override val stableKey: String = FavoriteOrderStore.onlineKey(track.key)
    }
}

internal class PlaylistPlaybackQueue {
    var entries: List<PlaylistPlaybackEntry> = emptyList()
        private set

    var selectedIndex: Int = 0
        private set

    val isActive: Boolean
        get() = entries.isNotEmpty()

    fun set(
        songs: List<Song>,
        onlineTracks: List<OnlineTrack>,
        order: List<String>,
        selectedKey: String
    ) {
        val unsorted = songs.map(PlaylistPlaybackEntry::Local) +
            onlineTracks.map(PlaylistPlaybackEntry::Online)
        entries = if (order.isEmpty()) {
            unsorted
        } else {
            val positions = order.withIndex().associate { it.value to it.index }
            unsorted.withIndex()
                .sortedWith(
                    compareBy<IndexedValue<PlaylistPlaybackEntry>> {
                        positions[it.value.stableKey] ?: Int.MAX_VALUE
                    }.thenBy { it.index }
                )
                .map { it.value }
        }
        selectedIndex = entries.indexOfFirst { it.stableKey == selectedKey }
            .takeIf { it >= 0 }
            ?: 0
    }

    fun clear() {
        entries = emptyList()
        selectedIndex = 0
    }

    fun current(): PlaylistPlaybackEntry? = entries.getOrNull(selectedIndex)

    fun selectLocal(songId: Long): Boolean =
        select(FavoriteOrderStore.localKey(songId))

    fun selectOnline(key: ProviderTrackKey): Boolean =
        select(FavoriteOrderStore.onlineKey(key))

    fun entryForLocalSong(songId: Long): PlaylistPlaybackEntry.Local? =
        entries.filterIsInstance<PlaylistPlaybackEntry.Local>()
            .firstOrNull { it.song.id == songId }

    fun entryForOnlineSongId(songId: Long): PlaylistPlaybackEntry.Online? =
        entries.filterIsInstance<PlaylistPlaybackEntry.Online>()
            .firstOrNull { pluginShellSongId(it.track.key) == songId }

    fun next(): PlaylistPlaybackEntry? {
        if (entries.isEmpty()) return null
        selectedIndex = (selectedIndex + 1) % entries.size
        return current()
    }

    fun previous(): PlaylistPlaybackEntry? {
        if (entries.isEmpty()) return null
        selectedIndex = if (selectedIndex == 0) entries.lastIndex else selectedIndex - 1
        return current()
    }

    fun replaceSelected(requested: OnlineTrack, resolved: OnlineTrack) {
        val current = current() as? PlaylistPlaybackEntry.Online ?: return
        if (current.track.key != requested.key) return
        entries = entries.toMutableList().apply {
            this[selectedIndex] = PlaylistPlaybackEntry.Online(resolved)
        }
    }

    fun remove(stableKey: String): Boolean {
        val index = entries.indexOfFirst { it.stableKey == stableKey }
        if (index < 0) return false
        entries = entries.toMutableList().apply { removeAt(index) }
        if (index < selectedIndex) selectedIndex--
        selectedIndex = selectedIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        return true
    }

    fun retainCurrent() {
        entries = current()?.let(::listOf).orEmpty()
        selectedIndex = 0
    }

    fun enqueueNext(entry: PlaylistPlaybackEntry) {
        if (entries.isEmpty()) {
            entries = listOf(entry)
            selectedIndex = 0
            return
        }
        val selectedKey = current()?.stableKey
        val withoutEntry = entries.filterNot { it.stableKey == entry.stableKey }
        val currentPosition = withoutEntry.indexOfFirst { it.stableKey == selectedKey }
            .takeIf { it >= 0 }
            ?: 0
        entries = withoutEntry.toMutableList().apply {
            add((currentPosition + 1).coerceAtMost(size), entry)
        }
        selectedIndex = currentPosition
    }

    private fun select(stableKey: String): Boolean {
        val index = entries.indexOfFirst { it.stableKey == stableKey }
        if (index < 0) return false
        selectedIndex = index
        return true
    }
}
