package com.ncm.app.data.model

data class DiscoverHomeResponse(
    val banners: List<BannerItem> = emptyList(),
    val playlists: List<PinnedPlaylist> = emptyList(),
    val dailySongs: List<Song> = emptyList(),
    val newSongs: List<Song> = emptyList(),
    val error: String? = null
)

data class BannerItem(
    val pic: String,
    val targetId: Long = 0,
    val targetType: Int = 0,
    val typeTitle: String? = null
)

data class PinnedPlaylist(
    val id: Long,
    val name: String,
    val picUrl: String? = null,
    val playCount: Long = 0,
    val trackCount: Int = 0,
    val copywriter: String? = null
)

data class QuickEntry(
    val id: Long = 0,
    val title: String = "",
    val subtitle: String = "",
    val imageUrl: String? = null,
    val playCount: Long = 0
)

data class SearchResponse(
    val songs: List<Song> = emptyList(),
    val songCount: Int = 0,
    val error: String? = null
)

data class Song(
    val id: Long = 0,
    val name: String = "",
    val artists: List<ArtistBrief>? = null,
    val album: AlbumBrief? = null,
    val dt: Long = 0,
    val fee: Int = 0,
    val mv: Long = 0,
    val pop: Int = 0,
    val mediaFileName: String? = null
) {
    val artistText: String
        get() = artists?.joinToString(" / ") { it.name } ?: "未知"

    val durationText: String
        get() {
            val totalSec = (dt / 1000).toInt()
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
}

data class ArtistBrief(
    val id: Long,
    val name: String
)

data class ArtistDetail(
    val id: Long,
    val name: String,
    val avatarUrl: String? = null,
    val aliases: List<String> = emptyList(),
    val briefDescription: String = "",
    val fullDescription: String = "",
    val musicCount: Int = 0,
    val albumCount: Int = 0,
    val mvCount: Int = 0,
    val hotSongs: List<Song> = emptyList()
)

data class AlbumBrief(
    val id: Long,
    val name: String,
    val picUrl: String? = null
)

internal fun Song.withArtworkFrom(detail: Song?): Song {
    if (!album?.picUrl.isNullOrBlank() || detail?.id != id) return this

    val detailAlbum = detail.album ?: return this
    val artworkUrl = detailAlbum.picUrl?.takeIf { it.isNotBlank() } ?: return this
    val mergedAlbum = album?.copy(
        id = album.id.takeIf { it > 0 } ?: detailAlbum.id,
        name = album.name.ifBlank { detailAlbum.name },
        picUrl = artworkUrl
    ) ?: detailAlbum

    return copy(album = mergedAlbum)
}

data class SongUrlResponse(
    val url: String? = null,
    val source: String = "netease",
    val br: Int = 0,
    val size: Long = 0,
    val type: String? = null,
    val encodeType: String? = null,
    val level: String? = null,
    val freeTrialInfo: Any? = null,
    val code: Int = 200,
    val loggedIn: Boolean = false,
    val error: String? = null
)

data class SongDetailResponse(
    val songs: List<Song> = emptyList(),
    val error: String? = null
)

data class LyricResponse(
    val lyric: String = "",
    val tlyric: String = "",
    val yrc: String = "",
    val error: String? = null
)

data class PlaylistTracksResponse(
    val playlist: PlaylistMeta? = null,
    val tracks: List<Song> = emptyList(),
    val error: String? = null
)

data class PlaylistMeta(
    val id: Long = 0,
    val name: String = "",
    val cover: String? = null,
    val trackCount: Int = 0
)

data class Playlist(
    val id: Long,
    val name: String,
    val cover: String? = null,
    val trackCount: Int = 0,
    val playCount: Long = 0,
    val creator: String? = null,
    val subscribed: Boolean = false
)
