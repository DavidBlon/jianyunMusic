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
    val pop: Int = 0
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

data class AlbumBrief(
    val id: Long,
    val name: String,
    val picUrl: String? = null
)

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

data class UserPlaylistResponse(
    val loggedIn: Boolean = false,
    val userId: Long = 0,
    val playlists: List<Playlist> = emptyList(),
    val error: String? = null
)

data class LoginStatusResponse(
    val loggedIn: Boolean = false,
    val userId: Long = 0,
    val nickname: String? = null,
    val avatar: String? = null,
    val vipType: Int = 0
)

data class UserProfile(
    val userId: Long,
    val nickname: String,
    val avatar: String? = null,
    val vipType: Int = 0
)

data class UserStats(
    val listenCount: Int = 0,
    val followCount: Int = 0,
    val fanCount: Int = 0,
    val eventCount: Int = 0
)

data class QrKeyResponse(
    val key: String? = null,
    val error: String? = null
)

data class QrCreateResponse(
    val img: String? = null,
    val url: String? = null,
    val error: String? = null
)

data class QrCheckResponse(
    val code: Int = 0,
    val message: String? = null,
    val nickname: String? = null,
    val avatar: String? = null,
    val error: String? = null
)

data class LoginCookieResponse(
    val loggedIn: Boolean = false,
    val saved: Boolean = false,
    val userId: Long = 0,
    val nickname: String? = null,
    val avatar: String? = null,
    val error: String? = null
)

data class LikeResponse(
    val loggedIn: Boolean = false,
    val id: Long = 0,
    val liked: Boolean = false,
    val code: Int = 200,
    val error: String? = null
)

data class LikeCheckResponse(
    val loggedIn: Boolean = false,
    val liked: Map<String, Boolean> = emptyMap(),
    val error: String? = null
)

data class ApiStatusResponse(
    val ok: Boolean = false,
    val error: String? = null
)
