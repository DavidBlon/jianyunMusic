package com.ncm.app.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ncm.app.data.SessionManager
import com.ncm.app.data.api.NeteaseApi
import com.ncm.app.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class MusicRepository(
    private val api: NeteaseApi,
    private val session: SessionManager
) {

    private companion object {
        private const val NEW_SONG_CHART_FALLBACK_ID = 3779629L
    }

    suspend fun getDiscoverHome(): Result<DiscoverHomeResponse> = safeCall {
        val banners = api.getBanners().array("banners").mapNotNull { it.objOrNull()?.toBanner() }
        val playlists = api.getPersonalizedPlaylists().array("result").mapNotNull { it.objOrNull()?.toPinnedPlaylist() }
        val dailySongs = playlists.firstOrNull()?.let { playlist ->
            getPlaylistTracks(playlist.id).getOrNull()?.tracks?.take(12)
        }.orEmpty()
        val newSongChartId = api.getToplistDetail()
            .array("list")
            .mapNotNull { it.objOrNull() }
            .firstOrNull { chart -> chart.string("name").orEmpty().contains("新歌") }
            ?.long("id")
            ?.takeIf { it > 0 }
            ?: NEW_SONG_CHART_FALLBACK_ID
        val newSongs = getPlaylistTracks(newSongChartId).getOrNull()?.tracks?.take(5).orEmpty()
        DiscoverHomeResponse(banners = banners, playlists = playlists, dailySongs = dailySongs, newSongs = newSongs)
    }

    suspend fun search(keywords: String, type: Int = 1, offset: Int = 0): Result<SearchResponse> = safeCall {
        val result = api.search(keywords, type, offset = offset).obj("result")
        SearchResponse(
            songs = result.array("songs").mapNotNull { it.objOrNull()?.toSong() },
            songCount = result.int("songCount")
        )
    }

    suspend fun getSongDetail(ids: List<Long>): Result<List<Song>> = safeCall {
        api.getSongDetail(ids.joinToString(",", prefix = "[", postfix = "]"))
            .array("songs")
            .mapNotNull { it.objOrNull()?.toSong() }
    }

    private val unblockManager = UnblockManager()

    suspend fun getSongUrl(songId: Long, br: Int = 128000, fee: Int = 0): Result<SongUrlResponse> = safeCall {
        // Step 1: 先尝试从网易云官方 API 获取
        val item = api.getSongUrl("[$songId]", br).array("data").firstOrNull()?.objOrNull()
        val rawUrl = item?.string("url")
        val playableUrl = rawUrl?.replaceFirst("http://", "https://")
        val code = item?.int("code") ?: 404

        if (!playableUrl.isNullOrBlank()) {
            // 官方的 URL 可用，直接返回
            return@safeCall SongUrlResponse(
                url = playableUrl,
                source = "netease",
                br = item?.int("br") ?: 0,
                size = item?.long("size") ?: 0,
                type = item?.string("type"),
                encodeType = item?.string("encodeType"),
                freeTrialInfo = item?.get("freeTrialInfo"),
                code = code,
                loggedIn = session.isLoggedIn,
                error = null
            )
        }

        // Step 2: 官方不可用，尝试从本地替代音源获取
        try {
            // 先获取歌曲详情用于匹配
            val songs = getSongDetail(listOf(songId)).getOrNull().orEmpty()
            val song = songs.firstOrNull()
            val info = UnblockManager.SongInfo(
                id = songId,
                name = song?.name ?: "",
                artists = song?.artists.orEmpty(),
                duration = song?.dt ?: 0,
                quality = br.toBackupQuality()
            )
            val result = unblockManager.unblock(info)
            if (result != null) {
                return@safeCall SongUrlResponse(
                    url = result.url,
                    source = result.source,
                    br = result.bitrate,
                    type = "mp3",
                    code = 200,
                    loggedIn = session.isLoggedIn,
                    error = null
                )
            }
        } catch (_: Exception) {
            // 替代音源不可用，忽略
        }

        // Step 3: 都失败，返回原始错误
        SongUrlResponse(
            url = null,
            br = item?.int("br") ?: 0,
            size = item?.long("size") ?: 0,
            code = code,
            loggedIn = session.isLoggedIn,
            error = playbackUnavailableMessage(item, null, code, fee)
        )
    }

    suspend fun getLyric(id: Long): Result<LyricResponse> = safeCall {
        val body = api.getLyric(id)
        LyricResponse(
            lyric = body.obj("lrc").string("lyric").orEmpty(),
            tlyric = body.obj("tlyric").string("lyric").orEmpty(),
            yrc = body.obj("yrc").string("lyric").orEmpty()
        )
    }

    suspend fun getPlaylistTracks(id: Long): Result<PlaylistTracksResponse> = safeCall {
        val body = api.getPlaylistDetail(id)
        val playlist = body.obj("playlist").takeIf { it.size() > 0 } ?: body.obj("result")
        val tracks = playlist.array("tracks").ifEmpty { body.array("songs") }
        PlaylistTracksResponse(
            playlist = PlaylistMeta(
                id = playlist.long("id").takeIf { it > 0 } ?: id,
                name = playlist.string("name").orEmpty(),
                cover = playlist.string("coverImgUrl")?.httpsUrl(),
                trackCount = playlist.int("trackCount").takeIf { it > 0 } ?: tracks.size
            ),
            tracks = completePlaylistSongs(playlist, tracks)
        )
    }

    suspend fun getPrivateFm(): Result<List<QuickEntry>> = safeCall {
        getDiscoverHome().getOrThrow().dailySongs.map { song ->
            QuickEntry(
                id = song.id,
                title = song.name,
                subtitle = song.artistText,
                imageUrl = song.album?.picUrl
            )
        }
    }

    suspend fun getPodcastPrograms(): Result<List<QuickEntry>> = safeCall {
        api.getRecommendedPrograms()
            .array("programs")
            .mapNotNull { item ->
                val program = item.objOrNull() ?: return@mapNotNull null
                val radio = program.obj("radio")
                QuickEntry(
                    id = program.long("id"),
                    title = program.string("name").orEmpty(),
                    subtitle = radio.string("name").orEmpty(),
                    imageUrl = (program.string("coverUrl") ?: radio.string("picUrl"))?.httpsUrl()
                ).takeIf { it.title.isNotBlank() }
            }
    }

    suspend fun getRankings(): Result<List<QuickEntry>> = safeCall {
        api.getToplistDetail()
            .array("list")
            .mapNotNull { it.objOrNull()?.toQuickEntry() }
    }

    suspend fun getHotPlaylists(): Result<List<QuickEntry>> = safeCall {
        api.getTopPlaylists()
            .array("playlists")
            .mapNotNull { it.objOrNull()?.toQuickEntry() }
    }

    suspend fun getUserPlaylists(limit: Int = 50): Result<List<Playlist>> = safeCall {
        val userId = session.userId
        if (userId <= 0) {
            emptyList()
        } else {
            api.getUserPlaylists(userId, limit)
                .array("playlist")
                .mapNotNull { it.objOrNull()?.toPlaylist() }
        }
    }

    suspend fun getUserStats(): Result<UserStats> = safeCall {
        val userId = session.userId
        if (userId <= 0) {
            UserStats()
        } else {
            val body = api.getUserDetail(userId)
            val profile = body.obj("profile")
            UserStats(
                listenCount = body.int("listenSongs"),
                followCount = profile.int("follows"),
                fanCount = profile.int("followeds"),
                eventCount = profile.int("eventCount")
            )
        }
    }

    suspend fun getLoginStatus(): Result<LoginStatusResponse> = safeCall {
        loadAccountFromCookie()
    }

    suspend fun getQrKey(): Result<String> = safeCall {
        throw UnsupportedOperationException("当前版本使用网页登录。")
    }

    suspend fun createQrCode(key: String): Result<QrCreateResponse> = safeCall {
        throw UnsupportedOperationException("当前版本使用网页登录。")
    }

    suspend fun checkQrCode(key: String): Result<QrCheckResponse> = safeCall {
        throw UnsupportedOperationException("当前版本使用网页登录。")
    }

    suspend fun loginByCookie(cookie: String): Result<LoginCookieResponse> = safeCall {
        session.saveCookie(cookie)
        val status = runCatching { loadAccountFromCookie() }
            .getOrElse { LoginStatusResponse(loggedIn = session.cookie.contains("MUSIC_U")) }
        LoginCookieResponse(
            loggedIn = status.loggedIn || session.cookie.contains("MUSIC_U"),
            saved = session.cookie.contains("MUSIC_U"),
            userId = status.userId,
            nickname = status.nickname,
            avatar = status.avatar,
            error = if (status.loggedIn || session.cookie.contains("MUSIC_U")) null else "登录状态无效，请重新登录。"
        )
    }

    suspend fun logout(): Result<ApiStatusResponse> = safeCall {
        session.clear()
        ApiStatusResponse(ok = true)
    }

    suspend fun refreshSession(): Result<LoginStatusResponse> = safeCall {
        if (session.cookie.isBlank()) {
            LoginStatusResponse(loggedIn = false)
        } else {
            loadAccountFromCookie()
        }
    }

    suspend fun likeSong(id: Long, like: Boolean = true): Result<LikeResponse> = safeCall {
        if (!session.isLoggedIn) {
            LikeResponse(loggedIn = false, id = id, liked = false, code = 401, error = "请先登录")
        } else {
            val likedPlaylistId = getLikedPlaylistId()
            val body = if (likedPlaylistId > 0) {
                api.manipulatePlaylistTracks(
                    operation = if (like) "add" else "del",
                    playlistId = likedPlaylistId,
                    trackIds = "[$id]",
                    timestamp = System.currentTimeMillis()
                )
            } else {
                api.likeSong(
                    alg = "itembased",
                    songId = id,
                    like = like,
                    timestamp = System.currentTimeMillis()
                )
            }
            val code = body.int("code").takeIf { it > 0 } ?: body.int("status").takeIf { it > 0 } ?: 200
            LikeResponse(
                loggedIn = true,
                id = id,
                liked = like,
                code = code,
                error = body.string("message") ?: body.string("msg")
            )
        }
    }

    private suspend fun getLikedPlaylistId(): Long {
        val userId = session.userId
        if (userId <= 0) return 0
        return api.getUserPlaylists(userId, limit = 100)
            .array("playlist")
            .mapNotNull { it.objOrNull() }
            .firstOrNull { playlist ->
                val name = playlist.string("name").orEmpty()
                name.contains("喜欢") || name.contains("收藏")
            }
            ?.long("id")
            ?: 0
    }

    suspend fun checkLiked(ids: List<Long>): Result<Map<String, Boolean>> = safeCall {
        val userId = session.userId
        if (!session.isLoggedIn || userId <= 0 || ids.isEmpty()) {
            emptyMap()
        } else {
            val body = api.getLikedSongIds(userId, System.currentTimeMillis())
            val likedIds = body.array("ids").mapNotNull { it.longOrNull() }.toSet()
            ids.associate { id -> id.toString() to likedIds.contains(id) }
        }
    }

    private suspend fun <T> safeCall(call: suspend () -> T): Result<T> {
        return withContext(Dispatchers.IO) {
            try {
                Result.success(call())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun playbackUnavailableMessage(item: JsonObject?, playableUrl: String?, code: Int, fee: Int): String? {
        if (!playableUrl.isNullOrBlank()) return null

        val serverMessage = item?.string("message") ?: item?.string("msg")
        val hasFreeTrial = item?.get("freeTrialInfo")?.let { !it.isJsonNull } == true
        return when {
            hasFreeTrial -> "这首歌当前只能试听，暂不支持播放完整版。"
            fee == 1 -> "这首歌需要网易云 VIP，当前账号无法播放完整版。"
            fee == 4 -> "这首歌需要单独购买后才能播放。"
            code == 404 -> "这首歌暂无可用版权或播放地址。"
            !serverMessage.isNullOrBlank() -> serverMessage
            !session.isLoggedIn -> "当前未登录，部分会员或版权歌曲无法播放。"
            else -> "这首歌当前不可播放，可能需要会员、购买或暂无版权。"
        }
    }

    private suspend fun completePlaylistSongs(playlist: JsonObject, rawTracks: List<JsonElement>): List<Song> {
        val parsedTracks = rawTracks.mapNotNull { it.objOrNull()?.toSong() }
        val parsedById = parsedTracks.associateBy { it.id }
        val knownIds = parsedById.keys
        val trackIds = playlist.array("trackIds")
            .mapNotNull { it.objOrNull()?.long("id")?.takeIf { id -> id > 0 } }
        val missingIds = trackIds.filterNot { it in knownIds }

        if (missingIds.isEmpty()) return parsedTracks

        val extraSongs = missingIds
            .chunked(200)
            .flatMap { ids ->
                api.getSongDetail(ids.joinToString(",", prefix = "[", postfix = "]"))
                    .array("songs")
                    .mapNotNull { it.objOrNull()?.toSong() }
            }
            .associateBy { it.id }

        return trackIds
            .mapNotNull { id -> parsedById[id] ?: extraSongs[id] }
            .ifEmpty { parsedTracks + extraSongs.values }
    }

    private fun JsonObject.toBanner(): BannerItem? {
        return BannerItem(
            pic = string("pic").orEmpty().httpsUrl(),
            targetId = long("targetId"),
            targetType = int("targetType"),
            typeTitle = string("typeTitle")
        ).takeIf { it.pic.isNotBlank() }
    }

    private fun JsonObject.toPinnedPlaylist(): PinnedPlaylist? {
        return PinnedPlaylist(
            id = long("id"),
            name = string("name").orEmpty(),
            picUrl = string("picUrl")?.httpsUrl(),
            playCount = long("playCount"),
            trackCount = int("trackCount"),
            copywriter = string("copywriter")
        ).takeIf { it.id > 0 && it.name.isNotBlank() }
    }

    private fun JsonObject.toQuickEntry(): QuickEntry? {
        return QuickEntry(
            id = long("id"),
            title = string("name").orEmpty(),
            subtitle = string("copywriter") ?: string("description").orEmpty(),
            imageUrl = (string("picUrl") ?: string("coverImgUrl"))?.httpsUrl(),
            playCount = long("playCount")
        ).takeIf { it.id > 0 && it.title.isNotBlank() }
    }

    private fun JsonObject.toPlaylist(): Playlist? {
        return Playlist(
            id = long("id"),
            name = string("name").orEmpty(),
            cover = string("coverImgUrl")?.httpsUrl(),
            trackCount = int("trackCount"),
            playCount = long("playCount"),
            creator = obj("creator").string("nickname"),
            subscribed = bool("subscribed")
        ).takeIf { it.id > 0 && it.name.isNotBlank() }
    }

    private fun JsonObject.toSong(): Song? {
        val album = objOrNull("al") ?: objOrNull("album")
        return Song(
            id = long("id"),
            name = string("name").orEmpty(),
            artists = (array("ar").ifEmpty { array("artists") })
                .mapNotNull { it.objOrNull()?.toArtist() },
            album = album?.let {
                AlbumBrief(
                    id = it.long("id"),
                    name = it.string("name").orEmpty(),
                    picUrl = it.string("picUrl")?.httpsUrl()
                )
            },
            dt = long("dt").takeIf { it > 0 } ?: long("duration"),
            fee = int("fee"),
            mv = long("mv"),
            pop = int("pop")
        ).takeIf { it.id > 0 && it.name.isNotBlank() }
    }

    private fun JsonObject.toArtist(): ArtistBrief? {
        return ArtistBrief(
            id = long("id"),
            name = string("name").orEmpty()
        ).takeIf { it.name.isNotBlank() }
    }

    private fun JsonObject.obj(name: String): JsonObject = objOrNull(name) ?: JsonObject()

    private fun JsonObject.objOrNull(name: String): JsonObject? = get(name)?.objOrNull()

    private fun JsonElement.objOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.array(name: String): List<JsonElement> {
        val value = get(name)
        return if (value is JsonArray) value.toList() else emptyList()
    }

    private fun JsonObject.string(name: String): String? {
        val value = get(name)
        return if (value != null && !value.isJsonNull) value.asString else null
    }

    private fun JsonObject.int(name: String): Int = string(name)?.toIntOrNull() ?: 0

    private fun JsonObject.long(name: String): Long {
        val value = get(name)
        return when {
            value == null || value.isJsonNull -> 0L
            value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asDouble.toLong()
            else -> value.asString.toDoubleOrNull()?.toLong() ?: 0L
        }
    }

    private fun JsonElement.longOrNull(): Long? {
        return when {
            isJsonNull -> null
            isJsonPrimitive && asJsonPrimitive.isNumber -> asDouble.toLong()
            else -> asString.toDoubleOrNull()?.toLong()
        }
    }

    private fun JsonObject.bool(name: String): Boolean {
        val value = get(name)
        return if (value != null && !value.isJsonNull) value.asBoolean else false
    }

    private suspend fun loadAccountFromCookie(): LoginStatusResponse {
        val profile = api.getUserAccount().obj("profile")
        val userId = profile.long("userId")
        if (userId <= 0) {
            return LoginStatusResponse(loggedIn = session.cookie.contains("MUSIC_U"))
        }

        val status = LoginStatusResponse(
            loggedIn = true,
            userId = userId,
            nickname = profile.string("nickname"),
            avatar = profile.string("avatarUrl")?.httpsUrl(),
            vipType = profile.int("vipType")
        )
        session.saveLoginInfo(
            userId = status.userId,
            nickname = status.nickname.orEmpty(),
            avatar = status.avatar,
            vipType = status.vipType
        )
        return status
    }

    private fun String.httpsUrl(): String = replaceFirst("http://", "https://")

    private fun Int.toBackupQuality(): String = if (this <= 128000) "128k" else "320k"
}
