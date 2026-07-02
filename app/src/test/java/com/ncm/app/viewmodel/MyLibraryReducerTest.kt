package com.ncm.app.viewmodel

import com.ncm.app.data.model.Playlist
import com.ncm.app.data.model.PlaylistMeta
import com.ncm.app.data.model.Song
import com.ncm.app.data.model.UserProfile
import com.ncm.app.data.model.UserStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyLibraryReducerTest {

    @Test
    fun stateFrom_usesLikedPlaylistCount() {
        val state = MyLibraryReducer.stateFrom(
            profile = UserProfile(1, "tester"),
            playlists = listOf(
                Playlist(id = 10, name = "我喜欢的音乐", trackCount = 12),
                Playlist(id = 20, name = "其他歌单", trackCount = 2)
            ),
            stats = UserStats(listenCount = 100, followCount = 3)
        )

        assertEquals(12, state.likedCount)
        assertEquals(100, state.listenCount)
        assertEquals(3, state.followCount)
    }

    @Test
    fun applyLikedSongChange_addsSongToCachedLikedPlaylistOnce() {
        val current = MyUiState(
            playlists = listOf(Playlist(id = 10, name = "我喜欢的音乐", trackCount = 1)),
            likedCount = 1
        )
        val cached = PlaylistDetailUiState(
            playlist = PlaylistMeta(id = 10, name = "我喜欢的音乐", trackCount = 1),
            songs = listOf(Song(id = 1, name = "old")),
            loadedPlaylistId = 10
        )

        val (nextState, nextDetail) = MyLibraryReducer.applyLikedSongChange(
            current = current,
            cachedLikedPlaylist = cached,
            song = Song(id = 2, name = "new"),
            liked = true
        )

        assertEquals(2, nextState.likedCount)
        assertEquals(listOf(2L, 1L), nextDetail?.songs?.map { it.id })
        assertEquals(2, nextDetail?.playlist?.trackCount)
    }

    @Test
    fun applyLikedSongChange_removesSongAndDoesNotGoBelowZero() {
        val current = MyUiState(
            playlists = listOf(Playlist(id = 10, name = "收藏", trackCount = 0)),
            likedCount = 0
        )

        val (nextState, nextDetail) = MyLibraryReducer.applyLikedSongChange(
            current = current,
            cachedLikedPlaylist = PlaylistDetailUiState(
                playlist = PlaylistMeta(id = 10, name = "收藏", trackCount = 1),
                songs = listOf(Song(id = 2, name = "new")),
                loadedPlaylistId = 10
            ),
            song = Song(id = 2, name = "new"),
            liked = false
        )

        assertEquals(0, nextState.likedCount)
        assertTrue(nextDetail?.songs.orEmpty().isEmpty())
        assertEquals(0, nextDetail?.playlist?.trackCount)
    }
}
