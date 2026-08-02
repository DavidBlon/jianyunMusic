package com.ncm.app.data

import com.ncm.app.data.model.Song
import com.ncm.app.data.repository.JianyunOfficialContent

/** Keeps Jianyun official likes on-device because their IDs do not exist on NetEase Cloud Music. */
class JianyunFavoriteStore(
    private val cache: AppCache,
    private val userId: () -> Long
) {

    fun load(): List<Song> {
        val stored = cache.get<List<Song>>(favoritesCacheKey())
            .orEmpty()
            .filter { JianyunOfficialContent.isOfficialSongId(it.id) }
            .distinctBy(Song::id)
        val migrated = JianyunOfficialContent.fallbackSongs().filter { song ->
            cache.get<Boolean>(legacyLikeCacheKey(song.id)) == true &&
                stored.none { it.id == song.id }
        }
        if (migrated.isEmpty()) return stored

        return (migrated + stored).also { cache.put(favoritesCacheKey(), it) }
    }

    fun isLiked(song: Song): Boolean {
        val favorites = load()
        if (favorites.any { it.id == song.id }) return true
        if (cache.get<Boolean>(legacyLikeCacheKey(song.id)) != true) return false

        cache.put(favoritesCacheKey(), listOf(song) + favorites)
        return true
    }

    fun update(song: Song, liked: Boolean): List<Song> {
        val current = load()
        val updated = if (liked) {
            listOf(song) + current.filterNot { it.id == song.id }
        } else {
            current.filterNot { it.id == song.id }
        }
        cache.put(favoritesCacheKey(), updated)
        cache.put(legacyLikeCacheKey(song.id), liked)
        return updated
    }

    private fun favoritesCacheKey(): String = "jianyun_liked_songs:${userId()}"

    private fun legacyLikeCacheKey(songId: Long): String =
        "local_like:${userId()}:$songId"
}
