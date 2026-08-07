package com.ncm.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.ncm.app.NeteaseApp
import com.ncm.app.data.AppCache
import com.ncm.app.data.JianyunFavoriteStore
import com.ncm.app.data.cache.LinglanCachePolicy
import com.ncm.app.data.model.AlbumBrief
import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.data.model.PlaybackSource
import com.ncm.app.data.model.Song
import com.ncm.app.data.model.SongUrlResponse
import com.ncm.app.data.model.withArtworkFrom
import com.ncm.app.data.repository.JianyunOfficialContent
import com.ncm.app.data.weekly.PlayEventEntity
import com.ncm.app.playback.AppPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentSong: Song? = null,
    val songUrl: String? = null,
    val audioSource: String = PlaybackSource.NETEASE,
    val lyric: String? = null,
    val tlyric: String? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val playMode: PlayMode = PlayMode.SEQUENCE,
    val quality: PlaybackQuality = PlaybackQuality.STANDARD,
    val isLiked: Boolean = false,
    val isLikeUpdating: Boolean = false,
    val queue: List<Song> = emptyList(),
    val history: List<Song> = emptyList(),
    val sleepRemainingSeconds: Int? = null,
    val linglanCache: LinglanCacheUiState = LinglanCacheUiState(),
    val error: String? = null
)

data class LinglanCacheUiState(
    val songCount: Int = 0,
    val sizeBytes: Long = 0,
    val isLoading: Boolean = false,
    val isClearing: Boolean = false,
    val message: String? = null
)

enum class PlayMode {
    SEQUENCE, SHUFFLE, REPEAT_ONE
}

enum class PlaybackQuality(val label: String, val shortLabel: String, val bitrate: Int) {
    STANDARD("标准音质", "标准", 128000),
    HIGHER("较高音质", "较高", 192000),
    EXTREME("极高音质", "极高", 320000),
    LOSSLESS("无损音质", "无损", 999000)
}

class PlayerViewModel : ViewModel() {

    private companion object {
        private const val TAG = "PlayerViewModel"
        private const val PREPARED_QUEUE_WINDOW = 3
        private const val PLAY_REQUEST_DEBOUNCE_MS = 180L
        private const val QUEUE_PREFETCH_START_DELAY_MS = 700L
        private const val QUEUE_PREFETCH_REQUEST_DELAY_MS = 300L
        private const val BUFFERING_FALLBACK_TIMEOUT_MS = 18_000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val MIN_PROGRESS_UPDATE_MS = 400L
        private const val HISTORY_LIMIT = 100
    }

    private data class PreparedQueueItem(
        val song: Song,
        val url: String,
        val source: String,
        val cacheKey: String? = null
    )

    private val app = NeteaseApp.instance
    private val repo = app.repository
    private val session = app.session
    private val jianyunFavorites = JianyunFavoriteStore(app.cache) { session.userId }
    private val _state = MutableStateFlow(PlayerUiState(quality = savedQuality()))
    val state: StateFlow<PlayerUiState> = _state

    private var playQueue: List<Song> = emptyList()
    private var currentIndex: Int = 0
    private var playRequestJob: Job? = null
    private var playRequestToken: Long = 0
    private var progressJob: Job? = null
    private var queuePrefetchJob: Job? = null
    private var bufferingFallbackJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var queuePrefetchAnchorSongId: Long? = null
    private var transientBackupSongId: Long? = null
    private val preparedQueueItems = mutableMapOf<Long, PreparedQueueItem>()

    private val player = AppPlayer.player(app)

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _state.value = _state.value.copy(isLoading = true)
                    scheduleBufferingFallback()
                }
                Player.STATE_READY -> {
                    bufferingFallbackJob?.cancel()
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isPlaying = player.playWhenReady,
                        duration = player.duration.takeIf { it > 0 } ?: _state.value.duration,
                        error = null
                    )
                    startProgressUpdates()
                }
                Player.STATE_ENDED -> {
                    bufferingFallbackJob?.cancel()
                    _state.value = _state.value.copy(isPlaying = false, progress = 1f)
                    if (_state.value.playMode == PlayMode.REPEAT_ONE) {
                        player.seekTo(0)
                        player.play()
                    } else {
                        playNext()
                    }
                }
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
            if (isPlaying) startProgressUpdates()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaId = mediaItem?.mediaId
            val songId = mediaId?.toLongOrNull()
            if (songId == null) {
                // 插件轨道：mediaId 为 pluginId#remoteId 复合键（非 Long）
                syncPluginMediaItemState(mediaItem)
                return
            }
            if (_state.value.currentSong?.id == songId) return
            clearTransientBackupIfLeaving(songId)
            val prepared = preparedQueueItems[songId]
                ?: playQueue.firstOrNull { it.id == songId }?.let { song ->
                    PreparedQueueItem(
                        song,
                        mediaItem.localConfiguration?.uri.toString(),
                        AppPlayer.sourceFor(mediaItem) ?: PlaybackSource.NETEASE,
                        AppPlayer.cacheKeyFor(mediaItem)
                    )
                }
                ?: return

            playQueue.indexOfFirst { it.id == songId }
                .takeIf { it >= 0 }
                ?.let { currentIndex = it }

            AppPlayer.updateCurrentPlayback(prepared.song, prepared.source)
            AppPlayer.beginPlaybackSession(prepared.song)
            AppPlayer.refreshPlaybackNotification(app)
            _state.value = _state.value.copy(
                currentSong = prepared.song,
                songUrl = prepared.url,
                audioSource = prepared.source,
                duration = prepared.song.dt,
                lyric = null,
                tlyric = null,
                isPlaying = player.isPlaying,
                isLoading = false,
                currentPosition = 0,
                progress = 0f,
                isLiked = false,
                isLikeUpdating = false,
                error = null
            )
            refreshLiked(songId)
            rememberInHistory(prepared.song)
            loadLyric(songId)
            appendPreparedQueue(songId)
            prefetchQueueAfter(songId)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "playerError ${error.errorCodeName}", error)
            // P6T1：播放失败只提示，不再自动切换聆澜/酷狗兜底源（GC #6）
            showPlaybackError(error)
        }
    }

    init {
        AppPlayer.mediaSession(app)
        player.addListener(playerListener)
        _state.value = _state.value.copy(history = loadHistory())
        restoreFromActivePlayer()
        refreshLinglanCacheStats()
    }

    fun play(songId: Long) {
        val current = _state.value
        if (hasActiveMediaFor(songId)) {
            if (!player.isPlaying) player.play()
            restoreFromActivePlayer()
            return
        }
        if (current.currentSong?.id == songId && !current.songUrl.isNullOrBlank()) {
            if (!player.isPlaying) player.play()
            return
        }

        val requestToken = ++playRequestToken
        playRequestJob?.cancel()
        playRequestJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            repo.getSongDetail(listOf(songId)).onSuccess { songs ->
                if (!isActivePlayRequest(requestToken)) return@onSuccess
                val song = songs.firstOrNull()
                if (song == null) {
                    _state.value = _state.value.copy(isLoading = false, isPlaying = false, error = "歌曲不存在")
                    return@onSuccess
                }

                _state.value = _state.value.copy(
                    currentSong = song,
                    songUrl = null,
                    audioSource = "netease",
                    duration = song.dt,
                    lyric = null,
                    tlyric = null,
                    isPlaying = false,
                    currentPosition = 0,
                    progress = 0f,
                    isLiked = false,
                    isLikeUpdating = false
                )
                refreshLiked(songId)
                resolvePreparedSong(song, _state.value.quality.bitrate).onSuccess { prepared ->
                    if (!isCurrentSongRequest(requestToken, songId)) return@onSuccess
                    if (prepared == null) {
                        stopUnavailable("这首歌当前不可播放")
                    } else {
                        playPreparedSong(prepared, requestToken)
                    }
                }.onFailure { e ->
                    if (!isCurrentSongRequest(requestToken, songId)) return@onFailure
                    _state.value = _state.value.copy(isLoading = false, isPlaying = false, error = e.message)
                }

                repo.getLyric(songId).onSuccess { lrc ->
                    if (!isCurrentSongRequest(requestToken, songId)) return@onSuccess
                    _state.value = _state.value.copy(lyric = lrc.lyric, tlyric = lrc.tlyric)
                }
            }.onFailure { e ->
                if (!isActivePlayRequest(requestToken)) return@onFailure
                _state.value = _state.value.copy(isLoading = false, isPlaying = false, error = e.message)
            }
        }
    }

    /**
     * 插件轨道播放（spec §4）：解析 → SSRF 校验 → 单曲入队播放。
     * 队列/喜欢/历史等 Song 主键能力不适用于插件轨道（阶段 5 迁移主键后统一）。
     */
    fun playPluginTrack(track: com.ncm.app.plugin.model.OnlineTrack) {
        playRequestJob?.cancel()
        playRequestJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            app.playbackResolver.resolve(track, pluginQualityLabel(_state.value.quality)).onSuccess { media ->
                val shellSong = Song(
                    id = 0L,
                    name = track.title,
                    artists = track.artists.map { ArtistBrief(0L, it.name) },
                    album = track.album?.let { AlbumBrief(0L, it.name, it.artworkUrl) },
                    dt = track.durationMs ?: 0L
                )
                val source = "plugin:${track.key.pluginId}"
                val mediaItem = AppPlayer.pluginMediaItem(track, shellSong, media.url, media.headers, source)
                player.stop()
                player.setMediaItems(listOf(mediaItem))
                player.prepare()
                player.play()
                AppPlayer.startPlaybackService(app)
                AppPlayer.updateCurrentPlayback(shellSong, source)
                AppPlayer.beginPlaybackSession(shellSong)
                AppPlayer.refreshPlaybackNotification(app)
                _state.value = _state.value.copy(
                    currentSong = shellSong,
                    songUrl = media.url,
                    audioSource = source,
                    duration = shellSong.dt,
                    isPlaying = true,
                    isLoading = false,
                    error = null
                )
                loadPluginLyric(track)
            }.onFailure { e ->
                _state.value = _state.value.copy(isLoading = false, isPlaying = false, error = e.message)
            }
        }
    }

    private fun pluginQualityLabel(quality: PlaybackQuality): String? = when (quality) {
        PlaybackQuality.STANDARD -> "128k"
        PlaybackQuality.HIGHER -> "192k"
        PlaybackQuality.EXTREME -> "320k"
        PlaybackQuality.LOSSLESS -> "999k"
    }

    fun open(songId: Long) {
        val current = _state.value
        if (hasActiveMediaFor(songId)) {
            restoreFromActivePlayer()
            return
        }
        if (current.currentSong?.id == songId && !current.songUrl.isNullOrBlank()) return
        play(songId)
    }

    fun togglePlay() {
        if (_state.value.songUrl.isNullOrBlank()) {
            if (hasActiveMedia()) {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
                restoreFromActivePlayer()
                return
            }
            _state.value.currentSong?.let { play(it.id) }
            return
        }

        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
        restoreFromActivePlayer()
    }

    fun playNext() {
        if (playQueue.isEmpty()) {
            player.seekToNextMediaItem()
            AppPlayer.syncCurrentFromPlayer()
            AppPlayer.refreshPlaybackNotification(app)
            restoreFromActivePlayer()
            return
        }
        currentIndex = (currentIndex + 1) % playQueue.size
        playFromQueue(currentIndex)
    }

    fun playPrev() {
        if (playQueue.isEmpty()) {
            player.seekToPreviousMediaItem()
            AppPlayer.syncCurrentFromPlayer()
            AppPlayer.refreshPlaybackNotification(app)
            restoreFromActivePlayer()
            return
        }
        currentIndex = if (currentIndex - 1 < 0) playQueue.size - 1 else currentIndex - 1
        playFromQueue(currentIndex)
    }

    fun setProgress(progress: Float) {
        val duration = player.duration.takeIf { it > 0 } ?: return
        val position = (duration * progress.coerceIn(0f, 1f)).toLong()
        AppPlayer.sessionAccumulator.onSeekStarted()
        player.seekTo(position)
        _state.value = _state.value.copy(
            currentPosition = position,
            duration = duration,
            progress = position.toFloat() / duration.toFloat()
        )
    }

    fun setQueue(songs: List<Song>, startIndex: Int = 0) {
        playQueue = songs
        currentIndex = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        _state.value = _state.value.copy(queue = playQueue)
    }

    fun playFromQueue(songId: Long) {
        val index = playQueue.indexOfFirst { it.id == songId }
        if (index < 0) return
        currentIndex = index
        playFromQueue(index)
    }

    fun playFromHistory(song: Song) {
        val history = _state.value.history
        setQueue(history, history.indexOfFirst { it.id == song.id }.coerceAtLeast(0))
        playFromQueue(song.id)
    }

    fun removeFromQueue(songId: Long) {
        val index = playQueue.indexOfFirst { it.id == songId }
        if (index < 0 || _state.value.currentSong?.id == songId) return
        playQueue = playQueue.filterNot { it.id == songId }
        if (index < currentIndex) currentIndex--
        currentIndex = currentIndex.coerceIn(0, (playQueue.size - 1).coerceAtLeast(0))
        preparedQueueItems.remove(songId)
        _state.value = _state.value.copy(queue = playQueue)
    }

    fun clearQueue() {
        val current = _state.value.currentSong
        playQueue = current?.let(::listOf).orEmpty()
        currentIndex = 0
        preparedQueueItems.keys.retainAll(playQueue.map { it.id }.toSet())
        _state.value = _state.value.copy(queue = playQueue)
    }

    fun enqueueNext(song: Song) {
        val current = _state.value.currentSong
        if (current == null) {
            setQueue(listOf(song))
            playFromQueue(song.id)
            return
        }
        val withoutSong = playQueue.filterNot { it.id == song.id }
        val currentPosition = withoutSong.indexOfFirst { it.id == current.id }
        playQueue = if (currentPosition >= 0) {
            withoutSong.toMutableList().apply { add(currentPosition + 1, song) }
        } else {
            listOf(current, song) + withoutSong
        }
        currentIndex = playQueue.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        _state.value = _state.value.copy(queue = playQueue)
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _state.value = _state.value.copy(sleepRemainingSeconds = null)
            return
        }
        sleepTimerJob = viewModelScope.launch {
            var remaining = minutes * 60
            while (remaining > 0) {
                _state.value = _state.value.copy(sleepRemainingSeconds = remaining)
                delay(1_000)
                remaining--
            }
            player.pause()
            _state.value = _state.value.copy(sleepRemainingSeconds = null, isPlaying = false)
        }
    }

    fun togglePlayMode() {
        val modes = PlayMode.entries
        val next = modes[(_state.value.playMode.ordinal + 1) % modes.size]
        player.shuffleModeEnabled = next == PlayMode.SHUFFLE
        player.repeatMode = if (next == PlayMode.REPEAT_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _state.value = _state.value.copy(playMode = next)
    }

    fun setQuality(quality: PlaybackQuality) {
        session.playbackQuality = quality.name
        val currentSong = _state.value.currentSong
        val songId = currentSong?.id
        if (_state.value.quality == quality) return
        if (songId == null) {
            _state.value = _state.value.copy(quality = quality)
            return
        }

        val requestToken = ++playRequestToken
        playRequestJob?.cancel()
        playRequestJob = viewModelScope.launch {
            val resumePosition = player.currentPosition.coerceAtLeast(0)
            preparedQueueItems.clear()
            _state.value = _state.value.copy(quality = quality, isLoading = true, error = null)
            repo.getSongUrlWithFallbackTimeout(songId, quality.bitrate, currentSong.fee).onSuccess { urlResp ->
                if (!isCurrentSongRequest(requestToken, songId)) return@onSuccess
                val url = urlResp.url
                if (url.isNullOrBlank()) {
                    val message = urlResp.error ?: "无法获取${quality.label}播放地址"
                    if (_state.value.songUrl.isNullOrBlank()) {
                        stopUnavailable(message)
                    } else {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            isPlaying = player.isPlaying,
                            error = "${quality.label}不可用，已保留当前播放。$message"
                        )
                    }
                } else {
                    currentSong?.let { song ->
                        val prepared = preparedFromResponse(song, urlResp) ?: return@let
                        startPlayback(
                            song = prepared.song,
                            url = prepared.url,
                            startPosition = resumePosition,
                            source = prepared.source,
                            cacheKey = prepared.cacheKey
                        )
                    }
                }
            }.onFailure { e ->
                if (!isCurrentSongRequest(requestToken, songId)) return@onFailure
                _state.value = _state.value.copy(isLoading = false, isPlaying = false, error = e.message)
            }
        }
    }

    fun toggleLike(onChanged: (Song, Boolean) -> Unit = { _, _ -> }) {
        val songId = _state.value.currentSong?.id ?: return
        val song = _state.value.currentSong ?: return
        if (_state.value.isLikeUpdating) return

        val targetLiked = !_state.value.isLiked
        _state.value = _state.value.copy(isLiked = targetLiked, isLikeUpdating = true, error = null)
        if (JianyunOfficialContent.isOfficialSongId(songId)) {
            jianyunFavorites.update(song, targetLiked)
            _state.value = _state.value.copy(isLiked = targetLiked, isLikeUpdating = false)
            onChanged(song, targetLiked)
            return
        }
        // P6T2：网易云喜欢接口已移除；legacy 条目不支持点赞（spec §12）
        _state.value = _state.value.copy(
            isLiked = false,
            isLikeUpdating = false,
            error = "历史网易云条目不支持点赞，可先迁移到当前来源"
        )
    }

    fun refreshLinglanCacheStats() {
        val cacheState = _state.value.linglanCache
        if (cacheState.isLoading || cacheState.isClearing) return
        _state.value = _state.value.copy(
            linglanCache = cacheState.copy(isLoading = true)
        )
        viewModelScope.launch {
            runCatching { app.linglanAudioCache.stats() }
                .onSuccess { stats ->
                    _state.value = _state.value.copy(
                        linglanCache = _state.value.linglanCache.copy(
                            songCount = stats.songCount,
                            sizeBytes = stats.sizeBytes,
                            isLoading = false
                        )
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        linglanCache = _state.value.linglanCache.copy(
                            isLoading = false,
                            message = error.message ?: "无法读取聆澜缓存"
                        )
                    )
                }
        }
    }

    fun clearLinglanCache() {
        val cacheState = _state.value.linglanCache
        if (cacheState.isClearing) return
        _state.value = _state.value.copy(
            linglanCache = cacheState.copy(isClearing = true, message = null)
        )
        viewModelScope.launch {
            playRequestJob?.cancel()
            playRequestJob = null
            playRequestToken++
            queuePrefetchJob?.cancel()
            queuePrefetchJob = null
            queuePrefetchAnchorSongId = null

            val currentState = _state.value
            val currentSong = currentState.currentSong
            val currentUrl = currentState.songUrl
            val currentSource = currentState.audioSource
            val wasPlaying = player.isPlaying
            val resumePosition = player.currentPosition.coerceAtLeast(0)
            app.linglanAudioCache.cancelPendingWrites()

            if (
                currentSong != null &&
                currentSource == PlaybackSource.LINGLAN &&
                !currentUrl.isNullOrBlank()
            ) {
                // Release CacheDataSource first, then continue the already obtained URL directly.
                // This prevents the active stream from immediately writing the cleared entry back.
                player.pause()
                player.setMediaItem(
                    AppPlayer.mediaItem(
                        song = currentSong,
                        url = currentUrl,
                        source = currentSource,
                        cacheKey = null
                    ),
                    resumePosition
                )
                player.prepare()
                player.playWhenReady = wasPlaying
                AppPlayer.updateCurrentPlayback(currentSong, currentSource)
            } else if (
                currentSong != null &&
                currentSource == PlaybackSource.LINGLAN_CACHE
            ) {
                // A cache-only item has no valid upstream URL after deletion. Stop it without
                // silently spending another paid-source request; the user can explicitly replay.
                player.pause()
                player.stop()
                player.clearMediaItems()
                AppPlayer.stopPlaybackService(app)
                _state.value = _state.value.copy(
                    songUrl = null,
                    audioSource = PlaybackSource.NETEASE,
                    isPlaying = false,
                    isLoading = false,
                    currentPosition = 0,
                    progress = 0f,
                    error = "聆澜缓存已清空，点击播放可重新获取当前歌曲"
                )
            }

            // Give Media3's old CacheDataSource a frame to close its cache span before deletion.
            delay(120)
            removeLinglanItemsFromPreparedQueue()
            val result = runCatching { app.linglanAudioCache.clear() }
            result.onSuccess { stats ->
                _state.value = _state.value.copy(
                    linglanCache = _state.value.linglanCache.copy(
                        songCount = stats.songCount,
                        sizeBytes = stats.sizeBytes,
                        isClearing = false,
                        isLoading = false,
                        message = "聆澜缓存已清空"
                    )
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    linglanCache = _state.value.linglanCache.copy(
                        isClearing = false,
                        isLoading = false,
                        message = error.message ?: "清空聆澜缓存失败"
                    )
                )
            }
        }
    }

    private fun scheduleBufferingFallback() {
        val song = _state.value.currentSong ?: return
        val requestToken = playRequestToken
        bufferingFallbackJob?.cancel()
        bufferingFallbackJob = viewModelScope.launch {
            delay(BUFFERING_FALLBACK_TIMEOUT_MS)
            if (!isCurrentSongRequest(requestToken, song.id)) return@launch
            if (player.playbackState != Player.STATE_BUFFERING) return@launch
            // P6T1：缓冲超时只提示，不自动切换音源
            _state.value = _state.value.copy(error = "缓冲超时，请检查网络后重试")
        }
    }

    private fun showPlaybackError(error: PlaybackException) {
        _state.value = _state.value.copy(
            isLoading = false,
            isPlaying = false,
            error = "播放失败：${error.errorCodeName}"
        )
    }

    private fun savedQuality(): PlaybackQuality {
        return PlaybackQuality.entries.firstOrNull { it.name == session.playbackQuality } ?: PlaybackQuality.STANDARD
    }

    private fun refreshLiked(songId: Long) {
        if (JianyunOfficialContent.isOfficialSongId(songId)) {
            val song = _state.value.currentSong?.takeIf { it.id == songId }
            _state.value = _state.value.copy(
                isLiked = song?.let(jianyunFavorites::isLiked)
                    ?: jianyunFavorites.load().any { it.id == songId },
                isLikeUpdating = false
            )
            return
        }
        // P6T2：网易云喜欢状态接口已移除；legacy 条目不显示喜欢态
        _state.value = _state.value.copy(isLiked = false, isLikeUpdating = false)
    }

    private fun isActivePlayRequest(requestToken: Long): Boolean {
        return requestToken == playRequestToken
    }

    private fun isCurrentSongRequest(requestToken: Long, songId: Long): Boolean {
        return isActivePlayRequest(requestToken) && _state.value.currentSong?.id == songId
    }

    private fun playFromQueue(index: Int) {
        val song = playQueue.getOrNull(index) ?: return
        clearTransientBackupIfLeaving(song.id)
        val current = _state.value
        if (current.currentSong?.id == song.id && !current.songUrl.isNullOrBlank()) {
            if (!player.isPlaying) player.play()
            return
        }

        val requestToken = ++playRequestToken
        playRequestJob?.cancel()
        playRequestJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            delay(PLAY_REQUEST_DEBOUNCE_MS)
            if (!isActivePlayRequest(requestToken)) return@launch
            if (seekToQueuedSong(song.id)) {
                return@launch
            }
            preparedQueueItems[song.id]?.let { prepared ->
                playPreparedSong(prepared, requestToken)
                return@launch
            }
            playKnownSong(song, requestToken)
        }
    }

    private fun restoreFromActivePlayer() {
        AppPlayer.syncCurrentFromPlayer()
        val song = AppPlayer.currentSong() ?: return
        val url = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (url.isNullOrBlank()) return
        _state.value = _state.value.copy(
            currentSong = song,
            songUrl = url,
            audioSource = AppPlayer.sourceFor(player.currentMediaItem) ?: AppPlayer.currentSource(),
            duration = player.duration.takeIf { it > 0 } ?: song.dt,
            isPlaying = player.isPlaying,
            isLoading = player.playbackState == Player.STATE_BUFFERING,
            currentPosition = player.currentPosition.coerceAtLeast(0),
            progress = player.duration.takeIf { it > 0 }?.let { duration ->
                player.currentPosition.coerceAtLeast(0).toFloat() / duration.toFloat()
            } ?: 0f,
            error = null
        )
        playQueue.indexOfFirst { it.id == song.id }
            .takeIf { it >= 0 }
            ?.let { currentIndex = it }
        if (player.isPlaying) startProgressUpdates()
        rememberInHistory(song)
    }

    private fun hasActiveMedia(): Boolean {
        return !player.currentMediaItem?.localConfiguration?.uri?.toString().isNullOrBlank()
    }

    private fun hasActiveMediaFor(songId: Long): Boolean {
        val mediaId = player.currentMediaItem?.mediaId?.toLongOrNull()
        return mediaId == songId && hasActiveMedia()
    }

    private suspend fun playKnownSong(song: Song, requestToken: Long) {
        val songId = song.id
        if (!isActivePlayRequest(requestToken)) return
        val playableSong = resolveSongArtwork(song)
        if (!isActivePlayRequest(requestToken)) return
        _state.value = _state.value.copy(
            currentSong = playableSong,
            songUrl = null,
            audioSource = "netease",
            duration = playableSong.dt,
            lyric = null,
            tlyric = null,
            isPlaying = false,
            currentPosition = 0,
            progress = 0f,
            isLiked = false,
            isLikeUpdating = false
        )
        refreshLiked(songId)
        resolvePreparedSong(playableSong, _state.value.quality.bitrate).onSuccess { prepared ->
            if (!isCurrentSongRequest(requestToken, songId)) return@onSuccess
            if (prepared == null) {
                stopUnavailable("\u8fd9\u9996\u6b4c\u5f53\u524d\u4e0d\u53ef\u64ad\u653e")
            } else {
                playPreparedSong(prepared, requestToken)
            }
        }.onFailure { e ->
            if (!isCurrentSongRequest(requestToken, songId)) return@onFailure
            _state.value = _state.value.copy(isLoading = false, isPlaying = false, error = e.message)
        }

        repo.getLyric(songId).onSuccess { lrc ->
            if (!isCurrentSongRequest(requestToken, songId)) return@onSuccess
            _state.value = _state.value.copy(lyric = lrc.lyric, tlyric = lrc.tlyric)
        }
    }

    private fun playPreparedSong(prepared: PreparedQueueItem, requestToken: Long) {
        if (!isActivePlayRequest(requestToken)) return
        currentIndex = playQueue.indexOfFirst { it.id == prepared.song.id }.takeIf { it >= 0 } ?: currentIndex
        if (
            seekToQueuedSong(
                songId = prepared.song.id,
                expectedUrl = prepared.url,
                expectedSource = prepared.source,
                expectedCacheKey = prepared.cacheKey
            )
        ) return
        progressJob?.cancel()
        _state.value = _state.value.copy(
            currentSong = prepared.song,
            songUrl = prepared.url,
            audioSource = prepared.source,
            duration = prepared.song.dt,
            lyric = null,
            tlyric = null,
            isPlaying = false,
            currentPosition = 0,
            progress = 0f,
            isLiked = false,
            isLikeUpdating = false
        )
        refreshLiked(prepared.song.id)
        rememberInHistory(prepared.song)
        startPlayback(
            prepared.song,
            prepared.url,
            source = prepared.source,
            cacheKey = prepared.cacheKey,
            reuseExistingQueue = playQueue.any { it.id == prepared.song.id }
        )
        loadLyric(prepared.song.id)
    }

    private fun startPlayback(
        song: Song,
        url: String,
        startPosition: Long = 0,
        source: String = PlaybackSource.NETEASE,
        cacheKey: String? = null,
        reuseExistingQueue: Boolean = false
    ) {
        bufferingFallbackJob?.cancel()
        progressJob?.cancel()
        transientBackupSongId = song.id.takeIf {
            LinglanCachePolicy.isTransientQueueSource(source)
        }
        AppPlayer.updateCurrentPlayback(song, source)
        AppPlayer.startPlaybackService(app)
        AppPlayer.refreshPlaybackNotification(app)
        val switchedInQueue = reuseExistingQueue &&
            switchToQueueItem(song, url, source, cacheKey, startPosition)
        if (!switchedInQueue) {
            player.setMediaItems(
                buildPlaybackQueue(song, url, source, cacheKey),
                0,
                startPosition.coerceAtLeast(0)
            )
            player.prepare()
            player.playWhenReady = true
        }
        if (
            source == PlaybackSource.LINGLAN &&
            LinglanCachePolicy.isLinglanCacheKey(cacheKey)
        ) {
            AppPlayer.scheduleLinglanCacheFill(url, checkNotNull(cacheKey))
        }
        _state.value = _state.value.copy(songUrl = url, audioSource = source, isLoading = true, error = null)
        prefetchQueueAfter(song.id)
    }

    private fun stopUnavailable(message: String) {
        bufferingFallbackJob?.cancel()
        progressJob?.cancel()
        player.stop()
        player.clearMediaItems()
        AppPlayer.stopPlaybackService(app)
        _state.value = _state.value.copy(
            songUrl = null,
            audioSource = "netease",
            isLoading = false,
            isPlaying = false,
            currentPosition = 0,
            progress = 0f,
            error = message
        )
    }

    private fun loadLyric(songId: Long) {
        viewModelScope.launch {
            repo.getLyric(songId).onSuccess { lrc ->
                if (_state.value.currentSong?.id == songId) {
                    _state.value = _state.value.copy(lyric = lrc.lyric, tlyric = lrc.tlyric)
                }
            }
        }
    }

    /** 插件轨道歌词：走歌曲自身绑定的插件（spec §11.3），不猜测其他来源。 */
    private fun loadPluginLyric(track: com.ncm.app.plugin.model.OnlineTrack) {
        viewModelScope.launch {
            app.playbackResolver.lyric(track).onSuccess { lrc ->
                _state.value = _state.value.copy(lyric = lrc.rawLrc, tlyric = lrc.translation)
            }.onFailure {
                // 插件缺歌词或请求失败 → 显示「暂无歌词」，不偷偷切换来源
                _state.value = _state.value.copy(lyric = null, tlyric = null)
            }
        }
    }

    /** 插件媒体项切换：从 AppPlayer 快照恢复状态（无队列索引/喜欢/历史等 Song 主键能力）。 */
    private fun syncPluginMediaItemState(mediaItem: MediaItem?) {
        AppPlayer.syncCurrentFromPlayer()
        val track = AppPlayer.currentPluginTrack() ?: return
        val shellSong = AppPlayer.currentSong() ?: return
        val source = AppPlayer.sourceFor(mediaItem) ?: "plugin:${track.key.pluginId}"
        _state.value = _state.value.copy(
            currentSong = shellSong,
            songUrl = mediaItem?.localConfiguration?.uri?.toString(),
            audioSource = source,
            duration = shellSong.dt,
            lyric = null,
            tlyric = null,
            isPlaying = player.isPlaying,
            isLoading = false,
            error = null
        )
        loadPluginLyric(track)
    }

    private fun prefetchQueueAfter(songId: Long) {
        if (playQueue.size <= 1) return
        if (queuePrefetchJob?.isActive == true) {
            if (queuePrefetchAnchorSongId != songId) {
                queuePrefetchJob?.cancel()
            } else {
                appendPreparedQueue(songId)
                return
            }
        }
        queuePrefetchAnchorSongId = songId
        queuePrefetchJob = viewModelScope.launch {
            delay(QUEUE_PREFETCH_START_DELAY_MS)
            if (_state.value.currentSong?.id != songId) return@launch
            appendPreparedQueue(songId)

            val bitrate = _state.value.quality.bitrate
            val songsToPrepare = PlaybackQueuePlanner.windowAfter(
                currentSongId = songId,
                playQueue = playQueue,
                currentIndex = currentIndex,
                windowSize = PREPARED_QUEUE_WINDOW
            )

            for (song in songsToPrepare) {
                if (_state.value.currentSong?.id != songId) break
                val existing = preparedQueueItems[song.id]
                if (existing != null && LinglanCachePolicy.isAllowedForPrefetch(existing.source)) {
                    continue
                }
                resolvePreparedSongForPrefetch(song, bitrate).onSuccess { prepared ->
                    if (prepared == null) return@onSuccess
                    if (_state.value.currentSong?.id == songId) {
                        appendPreparedQueue(songId)
                    }
                }.onFailure { e ->
                    Log.w(TAG, "prefetch failed song=${song.id}: ${e.message}")
                }
                delay(QUEUE_PREFETCH_REQUEST_DELAY_MS)
            }
        }
    }

    private suspend fun resolvePreparedSong(song: Song, bitrate: Int): Result<PreparedQueueItem?> {
        val playableSong = resolveSongArtwork(song)
        return repo.getSongUrlWithFallbackTimeout(
            playableSong.id,
            bitrate,
            playableSong.fee
        ).map { urlResp ->
            preparedFromResponse(playableSong, urlResp)?.also { prepared ->
                if (
                    LinglanCachePolicy.isTransientQueueSource(prepared.source) &&
                    _state.value.currentSong?.id == playableSong.id
                ) {
                    transientBackupSongId = playableSong.id
                }
                preparedQueueItems[playableSong.id] = prepared
            }
        }
    }

    private suspend fun resolvePreparedSongForPrefetch(
        song: Song,
        bitrate: Int
    ): Result<PreparedQueueItem?> {
        val playableSong = resolveSongArtwork(song)
        return repo.getSongUrlForPrefetch(
            playableSong.id,
            bitrate,
            playableSong.fee
        ).map { urlResp ->
            if (!LinglanCachePolicy.isAllowedForPrefetch(urlResp.source)) {
                null
            } else {
                preparedFromResponse(playableSong, urlResp)?.also { prepared ->
                    preparedQueueItems[playableSong.id] = prepared
                }
            }
        }
    }

    private fun preparedFromResponse(
        song: Song,
        response: SongUrlResponse
    ): PreparedQueueItem? {
        val url = response.url?.takeIf { it.isNotBlank() } ?: return null
        val cacheKey = if (LinglanCachePolicy.shouldPersist(response.source)) {
            app.linglanAudioCache.cacheKey(song.id, response.br)
        } else {
            null
        }
        return PreparedQueueItem(
            song = song,
            url = url,
            source = response.source,
            cacheKey = cacheKey
        )
    }

    private suspend fun resolveSongArtwork(song: Song): Song {
        if (!song.album?.picUrl.isNullOrBlank()) return song

        val detail = repo.getSongDetail(listOf(song.id)).getOrNull()?.firstOrNull()
        val enriched = song.withArtworkFrom(detail)
        if (enriched != song) {
            playQueue = playQueue.map { queued ->
                if (queued.id == enriched.id) queued.withArtworkFrom(detail) else queued
            }
            _state.value = _state.value.copy(queue = playQueue)
        }
        return enriched
    }

    private fun PreparedQueueItem.withSongArtworkFrom(song: Song): PreparedQueueItem {
        if (!this.song.album?.picUrl.isNullOrBlank()) return this
        val artwork = song.album?.picUrl?.takeIf { it.isNotBlank() } ?: return this
        return copy(
            song = this.song.copy(
                album = this.song.album?.copy(picUrl = artwork) ?: song.album
            )
        )
    }

    private fun seekToQueuedSong(
        songId: Long,
        expectedUrl: String? = null,
        expectedSource: String? = null,
        expectedCacheKey: String? = null
    ): Boolean {
        if (transientBackupSongId == songId) return false
        val targetIndex = (0 until player.mediaItemCount)
            .firstOrNull { index -> player.getMediaItemAt(index).mediaId.toLongOrNull() == songId }
            ?: return false
        val targetItem = player.getMediaItemAt(targetIndex)
        if (expectedUrl != null && targetItem.localConfiguration?.uri?.toString() != expectedUrl) return false
        if (
            expectedSource != null &&
            (AppPlayer.sourceFor(targetItem) ?: PlaybackSource.NETEASE) != expectedSource
        ) return false
        if (expectedCacheKey != null && AppPlayer.cacheKeyFor(targetItem) != expectedCacheKey) return false

        _state.value = _state.value.copy(isLoading = true, error = null)
        currentIndex = playQueue.indexOfFirst { it.id == songId }.takeIf { it >= 0 } ?: currentIndex
        player.seekTo(targetIndex, 0)
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        player.playWhenReady = true
        return true
    }

    private fun clearTransientBackupIfLeaving(nextSongId: Long) {
        val backupSongId = transientBackupSongId ?: return
        if (backupSongId == nextSongId) return
        preparedQueueItems[backupSongId]
            ?.takeIf { LinglanCachePolicy.isTransientQueueSource(it.source) }
            ?.let {
            preparedQueueItems.remove(backupSongId)
        }
        for (index in player.mediaItemCount - 1 downTo 0) {
            val item = player.getMediaItemAt(index)
            if (item.mediaId.toLongOrNull() != backupSongId) continue
            player.removeMediaItem(index)
        }
        transientBackupSongId = null
    }

    private fun removeLinglanItemsFromPreparedQueue() {
        preparedQueueItems.entries.removeAll { (_, prepared) ->
            LinglanCachePolicy.shouldPersist(prepared.source)
        }
        for (index in player.mediaItemCount - 1 downTo 0) {
            val item = player.getMediaItemAt(index)
            if (LinglanCachePolicy.isLinglanCacheKey(AppPlayer.cacheKeyFor(item))) {
                player.removeMediaItem(index)
            }
        }
    }

    private fun switchToQueueItem(
        song: Song,
        url: String,
        source: String,
        cacheKey: String?,
        startPosition: Long
    ): Boolean {
        if (player.mediaItemCount == 0 || player.currentMediaItem == null) return false
        if (transientBackupSongId == song.id) return false

        val requestedUrl = url
        val existingIndex = (0 until player.mediaItemCount)
            .firstOrNull { index -> player.getMediaItemAt(index).mediaId.toLongOrNull() == song.id }
        val targetIndex = existingIndex ?: run {
            val currentPlayerIndex = player.currentMediaItemIndex.takeIf { it >= 0 } ?: 0
            val insertIndex = (currentPlayerIndex + 1).coerceIn(0, player.mediaItemCount)
            player.addMediaItem(insertIndex, AppPlayer.mediaItem(song, url, source, cacheKey))
            insertIndex
        }
        existingIndex?.let { index ->
            val existingItem = player.getMediaItemAt(index)
            val existingUrl = existingItem.localConfiguration?.uri?.toString()
            val existingSource = AppPlayer.sourceFor(existingItem) ?: PlaybackSource.NETEASE
            val existingCacheKey = AppPlayer.cacheKeyFor(existingItem)
            if (
                existingUrl != requestedUrl ||
                existingSource != source ||
                existingCacheKey != cacheKey
            ) {
                player.replaceMediaItem(
                    index,
                    AppPlayer.mediaItem(song, requestedUrl, source, cacheKey)
                )
            }
        }

        player.seekTo(targetIndex, startPosition.coerceAtLeast(0))
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        player.playWhenReady = true
        return true
    }

    private fun buildPlaybackQueue(
        song: Song,
        url: String,
        source: String,
        cacheKey: String?
    ): List<MediaItem> {
        val mediaItems = mutableListOf(AppPlayer.mediaItem(song, url, source, cacheKey))
        if (playQueue.isEmpty()) return mediaItems

        PlaybackQueuePlanner.windowAfter(
            currentSongId = song.id,
            playQueue = playQueue,
            currentIndex = currentIndex,
            windowSize = PREPARED_QUEUE_WINDOW
        )
            .mapNotNull { nextSong ->
                preparedQueueItems[nextSong.id]?.let { prepared ->
                    prepared
                        .takeIf { LinglanCachePolicy.isAllowedForPrefetch(it.source) }
                        ?.let {
                            AppPlayer.mediaItem(
                                it.song,
                                it.url,
                                it.source,
                                it.cacheKey
                            )
                        }
                }
            }
            .forEach(mediaItems::add)
        return mediaItems
    }

    private fun appendPreparedQueue(currentSongId: Long) {
        if (playQueue.size <= 1) return
        val existingIds = (0 until player.mediaItemCount)
            .mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
            .toSet()
        PlaybackQueuePlanner.windowAfter(
            currentSongId = currentSongId,
            playQueue = playQueue,
            currentIndex = currentIndex,
            windowSize = PREPARED_QUEUE_WINDOW
        )
            .filter { it.id !in existingIds }
            .mapNotNull { song ->
                preparedQueueItems[song.id]?.let { prepared ->
                    prepared
                        .takeIf { LinglanCachePolicy.isAllowedForPrefetch(it.source) }
                        ?.let {
                            AppPlayer.mediaItem(
                                it.song,
                                it.url,
                                it.source,
                                it.cacheKey
                            )
                        }
                }
            }
            .forEach { mediaItem ->
                player.addMediaItem(mediaItem)
            }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            val accumulator = AppPlayer.sessionAccumulator
            while (player.isPlaying) {
                val rawDuration = player.duration
                val duration = rawDuration.takeIf { it > 0 } ?: 1
                val position = player.currentPosition.coerceAtLeast(0)
                accumulator.track(position, isPlaying = true)
                if (accumulator.consumeQualification(rawDuration)) {
                    recordQualifiedPlay(
                        songId = AppPlayer.currentPlaybackSessionSongId(),
                        playbackSessionId = AppPlayer.currentPlaybackSessionId(),
                        sessionStartedAt = AppPlayer.currentPlaybackSessionStartedAt()
                    )
                }
                val current = _state.value
                if (
                    kotlin.math.abs(position - current.currentPosition) >= MIN_PROGRESS_UPDATE_MS ||
                    duration != current.duration
                ) {
                    _state.value = current.copy(
                        currentPosition = position,
                        duration = duration,
                        progress = position.toFloat() / duration.toFloat()
                    )
                }
                delay(PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    /** 有效播放达标后，把当前会话写入每周播放记录（去重由唯一索引保证）。 */
    private fun recordQualifiedPlay(
        songId: Long,
        playbackSessionId: String?,
        sessionStartedAt: Long
    ) {
        val userId = session.userId
        if (userId <= 0 || songId <= 0L || playbackSessionId.isNullOrBlank()) return
        viewModelScope.launch {
            NeteaseApp.instance.weeklyPlayLog.record(
                PlayEventEntity(
                    userId = userId,
                    songId = songId,
                    playbackSessionId = playbackSessionId,
                    sessionStartedAt = sessionStartedAt
                )
            )
        }
    }

    private fun historyCacheKey(): String = AppCache.KEY_PLAY_HISTORY_PREFIX + session.userId

    fun dismissError(expectedMessage: String) {
        if (_state.value.error == expectedMessage) {
            _state.value = _state.value.copy(error = null)
        }
    }

    private fun loadHistory(): List<Song> = app.cache.get<List<Song>>(historyCacheKey()).orEmpty()

    private fun rememberInHistory(song: Song) {
        val history = (_state.value.history.filterNot { it.id == song.id } + song)
            .takeLast(HISTORY_LIMIT)
            .asReversed()
        app.cache.put(historyCacheKey(), history)
        _state.value = _state.value.copy(history = history)
    }

    override fun onCleared() {
        playRequestJob?.cancel()
        progressJob?.cancel()
        queuePrefetchJob?.cancel()
        bufferingFallbackJob?.cancel()
        sleepTimerJob?.cancel()
        player.removeListener(playerListener)
        if (_state.value.songUrl.isNullOrBlank()) {
            AppPlayer.stopPlaybackService(app)
        }
        super.onCleared()
    }
}
