package com.ncm.app.viewmodel

import com.ncm.app.data.model.Playlist
import com.ncm.app.data.model.Song

internal object MyLibraryReducer {

    fun isLikedPlaylistName(name: String): Boolean {
        return name.contains("喜欢") || name.contains("收藏")
    }

    fun stateFrom(profile: com.ncm.app.data.model.UserProfile, playlists: List<Playlist>, stats: com.ncm.app.data.model.UserStats): MyUiState {
        val liked = playlists.firstOrNull { isLikedPlaylistName(it.name) }?.trackCount ?: 0
        return MyUiState(
            profile = profile,
            playlists = playlists,
            isLoading = false,
            likedCount = liked,
            listenCount = stats.listenCount,
            followCount = stats.followCount
        )
    }

    fun addLocalLikedCount(playlists: List<Playlist>, localLikedCount: Int): List<Playlist> {
        if (localLikedCount <= 0) return playlists
        val likedPlaylistId = playlists.firstOrNull { isLikedPlaylistName(it.name) }?.id
            ?: return playlists
        return playlists.map { playlist ->
            if (playlist.id == likedPlaylistId) {
                playlist.copy(trackCount = playlist.trackCount + localLikedCount)
            } else {
                playlist
            }
        }
    }

    fun mergeLocalLikedSongs(
        remote: PlaylistDetailUiState,
        localSongs: List<Song>
    ): PlaylistDetailUiState {
        val distinctLocalSongs = localSongs.distinctBy(Song::id)
        val additions = distinctLocalSongs.filter { local ->
            remote.songs.none { it.id == local.id }
        }
        if (additions.isEmpty()) return remote

        val remoteTrackCount = maxOf(remote.playlist?.trackCount ?: 0, remote.songs.size)
        return remote.copy(
            playlist = remote.playlist?.copy(trackCount = remoteTrackCount + additions.size),
            songs = additions + remote.songs
        )
    }

    fun applyLikedSongChange(
        current: MyUiState,
        cachedLikedPlaylist: PlaylistDetailUiState?,
        song: Song,
        liked: Boolean
    ): Pair<MyUiState, PlaylistDetailUiState?> {
        val likedPlaylist = current.playlists.firstOrNull { isLikedPlaylistName(it.name) }
            ?: return current to cachedLikedPlaylist
        val likedPlaylistId = likedPlaylist.id

        val updatedPlaylists = current.playlists.map { playlist ->
            if (playlist.id != likedPlaylistId) {
                playlist
            } else {
                val nextCount = if (liked) playlist.trackCount + 1 else (playlist.trackCount - 1).coerceAtLeast(0)
                playlist.copy(trackCount = nextCount)
            }
        }
        val updatedMyState = current.copy(
            playlists = updatedPlaylists,
            likedCount = updatedPlaylists.firstOrNull { it.id == likedPlaylistId }?.trackCount ?: current.likedCount
        )

        val updatedDetail = cachedLikedPlaylist?.let { cached ->
            val nextSongs = if (liked) {
                if (cached.songs.any { it.id == song.id }) cached.songs else listOf(song) + cached.songs
            } else {
                cached.songs.filterNot { it.id == song.id }
            }
            val nextTrackCount = maxOf(nextSongs.size, updatedMyState.likedCount)
            cached.copy(
                playlist = cached.playlist?.copy(trackCount = nextTrackCount),
                songs = nextSongs
            )
        }

        return updatedMyState to updatedDetail
    }
}
