package com.ncm.app.data

import com.google.gson.Gson
import com.ncm.app.data.model.Playlist
import com.ncm.app.data.model.PlaylistMeta
import com.ncm.app.data.model.Song
import com.ncm.app.data.store.OnlineSongEntity
import com.ncm.app.data.store.toOnlineTrack
import com.ncm.app.data.store.toStoredEntity
import com.ncm.app.plugin.model.OnlineTrack

const val USER_PLAYLIST_ID_START = -20_000_000_000L

fun isUserPlaylistId(id: Long): Boolean = id <= USER_PLAYLIST_ID_START

enum class PlaylistMutationResult {
    ADDED,
    ALREADY_EXISTS,
    NOT_FOUND
}

data class UserPlaylistContent(
    val playlist: PlaylistMeta,
    val songs: List<Song>,
    val onlineTracks: List<OnlineTrack>
)

internal data class StoredUserPlaylist(
    val id: Long,
    val name: String,
    val createdAtEpochMs: Long,
    val songs: List<Song> = emptyList(),
    val onlineSongs: List<OnlineSongEntity> = emptyList()
)

/** On-device, account-scoped storage for playlists created by the user. */
class UserPlaylistStore(
    private val cache: AppCache,
    private val userId: () -> Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val gson: Gson = Gson()
) {

    @Synchronized
    fun summaries(): List<Playlist> = load().map { stored ->
        Playlist(
            id = stored.id,
            name = stored.name,
            cover = stored.songs.firstNotNullOfOrNull { it.album?.picUrl }
                ?: stored.onlineSongs.firstNotNullOfOrNull { it.artworkUrl },
            trackCount = stored.songs.size + stored.onlineSongs.size
        )
    }

    @Synchronized
    fun create(name: String): Long? {
        val normalizedName = name.trim().take(MAX_PLAYLIST_NAME_LENGTH)
        if (normalizedName.isBlank()) return null

        val playlists = load()
        val id = (playlists.minOfOrNull(StoredUserPlaylist::id) ?: (USER_PLAYLIST_ID_START + 1L)) - 1L
        save(
            playlists + StoredUserPlaylist(
                id = id,
                name = normalizedName,
                createdAtEpochMs = now()
            )
        )
        return id
    }

    @Synchronized
    fun delete(playlistId: Long): Boolean {
        val playlists = load()
        val updated = playlists.filterNot { it.id == playlistId }
        if (updated.size == playlists.size) return false
        save(updated)
        return true
    }

    @Synchronized
    fun content(playlistId: Long): UserPlaylistContent? {
        val stored = load().firstOrNull { it.id == playlistId } ?: return null
        val onlineTracks = stored.onlineSongs.mapNotNull { it.toOnlineTrack(gson) }
        val cover = stored.songs.firstNotNullOfOrNull { it.album?.picUrl }
            ?: onlineTracks.firstNotNullOfOrNull { it.artworkUrl ?: it.album?.artworkUrl }
        return UserPlaylistContent(
            playlist = PlaylistMeta(
                id = stored.id,
                name = stored.name,
                cover = cover,
                trackCount = stored.songs.size + onlineTracks.size
            ),
            songs = stored.songs,
            onlineTracks = onlineTracks
        )
    }

    @Synchronized
    fun addSong(playlistId: Long, song: Song): PlaylistMutationResult = update(playlistId) { stored ->
        if (stored.songs.any { it.id == song.id }) {
            null
        } else {
            stored.copy(songs = stored.songs + song)
        }
    }

    @Synchronized
    fun addOnlineTrack(playlistId: Long, track: OnlineTrack): PlaylistMutationResult =
        update(playlistId) { stored ->
            val entity = track.toStoredEntity(gson)
            if (stored.onlineSongs.any { it.pluginId == entity.pluginId && it.remoteId == entity.remoteId }) {
                null
            } else {
                stored.copy(onlineSongs = stored.onlineSongs + entity)
            }
        }

    @Synchronized
    fun removeSong(playlistId: Long, songId: Long): Boolean = remove(playlistId) { stored ->
        stored.copy(songs = stored.songs.filterNot { it.id == songId })
    }

    @Synchronized
    fun removeOnlineTrack(playlistId: Long, track: OnlineTrack): Boolean = remove(playlistId) { stored ->
        stored.copy(
            onlineSongs = stored.onlineSongs.filterNot {
                it.pluginId == track.key.pluginId && it.remoteId == track.key.remoteId
            }
        )
    }

    private fun update(
        playlistId: Long,
        transform: (StoredUserPlaylist) -> StoredUserPlaylist?
    ): PlaylistMutationResult {
        val playlists = load()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return PlaylistMutationResult.NOT_FOUND
        val replacement = transform(playlists[index]) ?: return PlaylistMutationResult.ALREADY_EXISTS
        save(playlists.toMutableList().apply { this[index] = replacement })
        return PlaylistMutationResult.ADDED
    }

    private fun remove(
        playlistId: Long,
        transform: (StoredUserPlaylist) -> StoredUserPlaylist
    ): Boolean {
        val playlists = load()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return false
        val original = playlists[index]
        val replacement = transform(original)
        if (replacement == original) return false
        save(playlists.toMutableList().apply { this[index] = replacement })
        return true
    }

    private fun load(): List<StoredUserPlaylist> =
        cache.get<List<StoredUserPlaylist>>(storageKey()).orEmpty()
            .filter { isUserPlaylistId(it.id) && it.name.isNotBlank() }
            .sortedBy(StoredUserPlaylist::createdAtEpochMs)

    private fun save(playlists: List<StoredUserPlaylist>) {
        cache.put(storageKey(), playlists)
    }

    private fun storageKey(): String = "$KEY_PREFIX${userId()}"

    companion object {
        const val MAX_PLAYLIST_NAME_LENGTH = 40
        private const val KEY_PREFIX = "user_playlists:"
    }
}
