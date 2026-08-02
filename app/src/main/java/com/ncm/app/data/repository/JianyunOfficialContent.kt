package com.ncm.app.data.repository

import com.google.gson.JsonParser
import com.ncm.app.BuildConfig
import com.ncm.app.data.model.AlbumBrief
import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.data.model.ArtistDetail
import com.ncm.app.data.model.PlaybackSource
import com.ncm.app.data.model.SearchResponse
import com.ncm.app.data.model.Song
import com.ncm.app.data.model.SongUrlResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/** Music published directly in the root of the Jianyun official website. */
object JianyunOfficialContent {
    const val SONG_ID = 9_900_000_001L
    const val ARTIST_ID = 9_900_000_002L
    const val ALBUM_ID = 9_900_000_003L

    const val SONG_NAME = "简云漫游"
    const val ARTIST_NAME = "简云官方"

    private const val DEFAULT_FILE_NAME = "$SONG_NAME.mp3"
    private const val ALBUM_COVER_PATH = "assets/app-icon.png"
    private const val ARTIST_PHOTO_PATH = "assets/%E7%AE%80%E5%A8%98.png"
    private const val CATALOG_PATH = "jianyun-music.php"
    private const val SONG_DURATION_MS = 109_000L
    private const val SONG_SIZE_BYTES = 2_596_176L
    private const val DYNAMIC_SONG_ID_BASE = 20_000_000_000L
    private const val DYNAMIC_SONG_ID_MAX = DYNAMIC_SONG_ID_BASE + 4_294_967_295L

    private val contentBaseUrl: String
        get() = BuildConfig.JIANYUN_CONTENT_BASE_URL.trim().trimEnd('/')

    val catalogUrl: String
        get() = "$contentBaseUrl/$CATALOG_PATH"

    val songUrl: String
        get() = songUrlForFile(DEFAULT_FILE_NAME)

    val albumCoverUrl: String
        get() = "$contentBaseUrl/$ALBUM_COVER_PATH"

    val artistPhotoUrl: String
        get() = "$contentBaseUrl/$ARTIST_PHOTO_PATH"

    fun defaultSong(): Song = song(
        fileName = DEFAULT_FILE_NAME,
        durationMs = SONG_DURATION_MS
    )

    fun song(
        fileName: String,
        displayName: String = fileName.substringBeforeLast('.'),
        durationMs: Long = 0L
    ): Song {
        val resolvedName = displayName.ifBlank { fileName.substringBeforeLast('.') }
        return Song(
            id = songIdForFile(fileName),
            name = resolvedName,
            artists = listOf(ArtistBrief(ARTIST_ID, ARTIST_NAME)),
            album = AlbumBrief(
                id = ALBUM_ID,
                name = resolvedName,
                picUrl = albumCoverUrl
            ),
            dt = durationMs.coerceAtLeast(0L),
            fee = 0,
            pop = 100
        )
    }

    fun artist(songs: List<Song> = listOf(defaultSong())): ArtistDetail = ArtistDetail(
        id = ARTIST_ID,
        name = ARTIST_NAME,
        avatarUrl = artistPhotoUrl,
        briefDescription = "简云音乐官方歌手",
        fullDescription = "简云音乐官方歌手",
        musicCount = songs.size,
        albumCount = if (songs.isEmpty()) 0 else 1,
        hotSongs = songs
    )

    fun fallbackSongs(): List<Song> = listOf(defaultSong())

    fun isOfficialSongId(id: Long): Boolean =
        id == SONG_ID || id in DYNAMIC_SONG_ID_BASE..DYNAMIC_SONG_ID_MAX

    fun artistOrNull(id: Long, songs: List<Song> = fallbackSongs()): ArtistDetail? =
        artist(songs).takeIf { id == ARTIST_ID }

    fun searchSongs(keywords: String, songs: List<Song> = fallbackSongs()): List<Song> {
        val query = keywords.trim()
        if (query.isBlank()) return emptyList()
        return songs.filter { candidate ->
            candidate.name.contains(query, ignoreCase = true) ||
                candidate.artistText.contains(query, ignoreCase = true)
        }
    }

    fun mergeSearchResponse(
        keywords: String,
        remote: SearchResponse,
        officialSongs: List<Song> = fallbackSongs()
    ): SearchResponse {
        val localMatches = searchSongs(keywords, officialSongs)
        if (localMatches.isEmpty()) return remote
        val merged = (localMatches + remote.songs).distinctBy(Song::id)
        val addedCount = merged.size - remote.songs.distinctBy(Song::id).size
        return remote.copy(
            songs = merged,
            songCount = (remote.songCount + addedCount).coerceAtLeast(merged.size)
        )
    }

    fun parseCatalog(json: String): List<Song> {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return emptyList()
        val items = runCatching { root.getAsJsonArray("songs") }.getOrNull()
            ?: return emptyList()
        return items.mapNotNull { element ->
            val item = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val fileName = item.get("file")
                ?.takeUnless { it.isJsonNull }
                ?.asString
                ?.trim()
                .orEmpty()
            if (!fileName.endsWith(".mp3", ignoreCase = true)) return@mapNotNull null
            val displayName = item.get("name")
                ?.takeUnless { it.isJsonNull }
                ?.asString
                ?.trim()
                .orEmpty()
                .ifBlank { fileName.substringBeforeLast('.') }
            val durationMs = item.get("durationMs")
                ?.takeUnless { it.isJsonNull }
                ?.let { runCatching { it.asLong }.getOrNull() }
                ?: if (fileName == DEFAULT_FILE_NAME) SONG_DURATION_MS else 0L
            song(fileName, displayName, durationMs)
        }
            .distinctBy(Song::id)
    }

    fun songUrlFor(song: Song): String = songUrlForFile("${song.name}.mp3")

    fun songUrlResponse(song: Song, bitrate: Int): SongUrlResponse = SongUrlResponse(
        url = songUrlFor(song),
        source = PlaybackSource.JIANYUN_OFFICIAL,
        br = bitrate,
        size = if (song.id == SONG_ID) SONG_SIZE_BYTES else 0L,
        type = "mp3",
        encodeType = "mp3",
        code = 200,
        loggedIn = false
    )

    private fun songIdForFile(fileName: String): Long {
        if (fileName == DEFAULT_FILE_NAME) return SONG_ID
        val checksum = CRC32().apply {
            update(fileName.lowercase().toByteArray(StandardCharsets.UTF_8))
        }.value
        return DYNAMIC_SONG_ID_BASE + checksum
    }

    private fun songUrlForFile(fileName: String): String {
        val encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "$contentBaseUrl/$encoded"
    }
}
