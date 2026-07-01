package com.ncm.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.ncm.app.NeteaseApp
import com.ncm.app.data.model.Song
import com.ncm.app.playback.AppPlayer
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentSong: Song? = null,
    val songUrl: String? = null,
    val audioSource: String = "netease",
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
    val error: String? = null
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
        private const val SONG_URL_CACHE_LIMIT = 80
        private const val PLAY_REQUEST_DEBOUNCE_MS = 180L
        private const val QUEUE_PREFETCH_START_DELAY_MS = 700L
        private const val QUEUE_PREFETCH_REQUEST_DELAY_MS = 300L
        private const val BUFFERING_FALLBACK_TIMEOUT_MS = 5_000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val MIN_PROGRESS_UPDATE_MS = 400L
    }

    private data class UrlCacheKey(
        val songId: Long,
        val bitrate: Int
    )

    private data class PreparedQueueItem(
        val song: Song,
        val url: String,
        val source: String
    )

    private val app = NeteaseApp.instance
    private val repo = app.repository
    private val session = app.session
    private val _state = MutableStateFlow(PlayerUiState(quality = savedQuality()))
    val state: StateFlow<PlayerUiState> = _state

    private var playQueue: List<Song> = emptyList()
    private var currentIndex: Int = 0
    private var playRequestJob: Job? = null
    private var playRequestToken: Long = 0
    private var progressJob: Job? = null
    private var queuePrefetchJob: Job? = null
    private var bufferingFallbackJob: Job? = null
    private var queuePrefetchAnchorSongId: Long? = null
    private var transientBackupSongId: Long? = null
    private val preparedQueueItems = mutableMapOf<Long, PreparedQueueItem>()
    private val pendingSongUrlRequests = mutableMapOf<UrlCacheKey, Deferred<Result<PreparedQueueItem?>>>()
    private val songUrlCache = object : LinkedHashMap<UrlCacheKey, PreparedQueueItem>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UrlCacheKey, PreparedQueueItem>?): Boolean {
            return size > SONG_URL_CACHE_LIMIT
        }
    }

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
            val songId = mediaItem?.mediaId?.toLongOrNull() ?: return
            if (_state.value.currentSong?.id == songId) return
            clearTransientBackupIfLeaving(songId)
            val prepared = preparedQueueItems[songId]
                ?: playQueue.firstOrNull { it.id == songId }?.let { song ->
                    PreparedQueueItem(song, mediaItem.localConfiguration?.uri.toString(), "netease")
                }
                ?: return

            playQueue.indexOfFirst { it.id == songId }
                .takeIf { it >= 0 }
                ?.let { currentIndex = it }

            AppPlayer.updateCurrentPlayback(prepared.song, prepared.source)
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
            loadLyric(songId)
            appendPreparedQueue(songId)
            prefetchQueueAfter(songId)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "playerError ${error.errorCodeName}", error)
            val song = _state.value.currentSong
            if (song != null && _state.value.audioSource == "netease") {
                switchToBackupSource(song.id, ++playRequestToken, "播放失败，正在切换备用音源")
            } else {
                _state.value = _state.value.copy(
                    isLoading = false,
                    isPlaying = false,
                    error = "播放失败：${error.errorCodeName}"
                )
            }
        }
    }

    init {
        AppPlayer.mediaSession(app)
        player.addListener(playerListener)
        restoreFromActivePlayer()
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

        viewModelScope.launch {
            val resumePosition = player.currentPosition.coerceAtLeast(0)
            val requestToken = playRequestToken
            preparedQueueItems.clear()
            songUrlCache.clear()
            pendingSongUrlRequests.clear()
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
                    currentSong?.let { startPlayback(it, url, resumePosition, urlResp.source) }
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
        viewModelScope.launch {
            repo.likeSong(songId, targetLiked).onSuccess { resp ->
                if (resp.loggedIn && resp.code in 200..299) {
                    repo.checkLiked(listOf(songId)).onSuccess { likedMap ->
                        val confirmedLiked = likedMap[songId.toString()] == true
                        if (confirmedLiked == targetLiked) {
                            _state.value = _state.value.copy(isLiked = confirmedLiked, isLikeUpdating = false)
                            onChanged(song, confirmedLiked)
                        } else {
                            _state.value = _state.value.copy(
                                isLiked = !targetLiked,
                                isLikeUpdating = false,
                                error = "喜欢状态没有同步到网易云，请稍后重试"
                            )
                        }
                    }.onFailure { e ->
                        _state.value = _state.value.copy(
                            isLiked = !targetLiked,
                            isLikeUpdating = false,
                            error = e.message ?: "无法确认网易云喜欢状态"
                        )
                    }
                } else {
                    _state.value = _state.value.copy(
                        isLiked = !targetLiked,
                        isLikeUpdating = false,
                        error = resp.error ?: "喜欢操作失败，请稍后重试"
                    )
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isLiked = !targetLiked,
                    isLikeUpdating = false,
                    error = e.message ?: "喜欢操作失败，请稍后重试"
                )
            }
        }
    }

    private fun scheduleBufferingFallback() {
        val song = _state.value.currentSong ?: return
        if (_state.value.audioSource != "netease") return
        val requestToken = playRequestToken
        bufferingFallbackJob?.cancel()
        bufferingFallbackJob = viewModelScope.launch {
            delay(BUFFERING_FALLBACK_TIMEOUT_MS)
            if (!isCurrentSongRequest(requestToken, song.id)) return@launch
            if (player.playbackState != Player.STATE_BUFFERING) return@launch
            switchToBackupSource(song.id, requestToken, "网易云音源加载超时，正在切换备用音源")
        }
    }

    private fun switchToBackupSource(songId: Long, requestToken: Long, message: String) {
        if (!isCurrentSongRequest(requestToken, songId)) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = message)
            repo.getBackupSongUrl(songId, _state.value.quality.bitrate).onSuccess { urlResp ->
                if (!isCurrentSongRequest(requestToken, songId)) return@onSuccess
                val url = urlResp.url
                val song = _state.value.currentSong
                if (url.isNullOrBlank() || song == null) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isPlaying = player.isPlaying,
                        error = urlResp.error ?: "备用音源暂时不可用"
                    )
                    return@onSuccess
                }
                val prepared = PreparedQueueItem(song, url, urlResp.source)
            preparedQueueItems[songId] = prepared
            transientBackupSongId = songId
            songUrlCache.remove(UrlCacheKey(songId, _state.value.quality.bitrate))
                progressJob?.cancel()
                _state.value = _state.value.copy(
                    currentSong = prepared.song,
                    songUrl = prepared.url,
                    audioSource = prepared.source,
                    duration = prepared.song.dt,
                    isPlaying = false,
                    isLoading = true,
                    error = null
                )
                startPlayback(prepared.song, prepared.url, source = prepared.source, reuseExistingQueue = false)
            }.onFailure { e ->
                if (!isCurrentSongRequest(requestToken, songId)) return@onFailure
                _state.value = _state.value.copy(
                    isLoading = false,
                    isPlaying = player.isPlaying,
                    error = e.message ?: "备用音源暂时不可用"
                )
            }
        }
    }

    private fun savedQuality(): PlaybackQuality {
        return PlaybackQuality.entries.firstOrNull { it.name == session.playbackQuality } ?: PlaybackQuality.STANDARD
    }

    private fun refreshLiked(songId: Long) {
        viewModelScope.launch {
            repo.checkLiked(listOf(songId)).onSuccess { liked ->
                if (_state.value.currentSong?.id == songId) {
                    _state.value = _state.value.copy(isLiked = liked[songId.toString()] == true)
                }
            }
        }
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
            audioSource = AppPlayer.currentSource(),
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
        if (seekToQueuedSong(prepared.song.id)) return
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
        startPlayback(
            prepared.song,
            prepared.url,
            source = prepared.source,
            reuseExistingQueue = playQueue.any { it.id == prepared.song.id }
        )
        loadLyric(prepared.song.id)
    }

    private fun startPlayback(
        song: Song,
        url: String,
        startPosition: Long = 0,
        source: String = "netease",
        reuseExistingQueue: Boolean = false
    ) {
        bufferingFallbackJob?.cancel()
        progressJob?.cancel()
        AppPlayer.updateCurrentPlayback(song, source)
        AppPlayer.startPlaybackService(app)
        AppPlayer.refreshPlaybackNotification(app)
        val switchedInQueue = reuseExistingQueue && switchToQueueItem(song, url, source, startPosition)
        if (!switchedInQueue) {
            player.setMediaItems(buildPlaybackQueue(song, url, source), 0, startPosition.coerceAtLeast(0))
            player.prepare()
            player.playWhenReady = true
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

            val startIndex = playQueue.indexOfFirst { it.id == songId }.takeIf { it >= 0 } ?: currentIndex
            val bitrate = _state.value.quality.bitrate
            val songsToPrepare = (1..PREPARED_QUEUE_WINDOW).mapNotNull { offset ->
                playQueue.getOrNull((startIndex + offset) % playQueue.size)
            }.filterNot { it.id == songId }
                .distinctBy { it.id }

            for (song in songsToPrepare) {
                if (_state.value.currentSong?.id != songId) break
                if (preparedQueueItems.containsKey(song.id) || songUrlCache.containsKey(UrlCacheKey(song.id, bitrate))) continue
                resolvePreparedSong(song, bitrate).onSuccess { prepared ->
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
        val key = UrlCacheKey(song.id, bitrate)
        songUrlCache[key]?.let { cached ->
            preparedQueueItems[song.id] = cached
            return Result.success(cached)
        }
        pendingSongUrlRequests[key]?.let { pending ->
            return pending.await().also { result ->
                result.getOrNull()?.let { prepared -> preparedQueueItems[song.id] = prepared }
            }
        }

        val request = viewModelScope.async {
            repo.getSongUrlWithFallbackTimeout(song.id, bitrate, song.fee).map { urlResp ->
                val url = urlResp.url ?: return@map null
                PreparedQueueItem(song, url, urlResp.source).also { prepared ->
                    if (prepared.source == "netease") {
                        songUrlCache[key] = prepared
                    } else if (_state.value.currentSong?.id == song.id) {
                        transientBackupSongId = song.id
                    }
                    preparedQueueItems[song.id] = prepared
                }
            }
        }
        pendingSongUrlRequests[key] = request
        return try {
            request.await()
        } finally {
            if (pendingSongUrlRequests[key] === request) {
                pendingSongUrlRequests.remove(key)
            }
        }
    }

    private fun seekToQueuedSong(songId: Long): Boolean {
        if (transientBackupSongId == songId) return false
        val targetIndex = (0 until player.mediaItemCount)
            .firstOrNull { index -> player.getMediaItemAt(index).mediaId.toLongOrNull() == songId }
            ?: return false

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
        preparedQueueItems[backupSongId]?.takeIf { it.source != "netease" }?.let {
            preparedQueueItems.remove(backupSongId)
        }
        (player.mediaItemCount - 1 downTo 0)
            .firstOrNull { index -> player.getMediaItemAt(index).mediaId.toLongOrNull() == backupSongId }
            ?.takeIf { index -> index != player.currentMediaItemIndex }
            ?.let(player::removeMediaItem)
        transientBackupSongId = null
    }

    private fun switchToQueueItem(song: Song, url: String, source: String, startPosition: Long): Boolean {
        if (player.mediaItemCount == 0 || player.currentMediaItem == null) return false
        if (transientBackupSongId == song.id) return false

        val existingIndex = (0 until player.mediaItemCount)
            .firstOrNull { index -> player.getMediaItemAt(index).mediaId.toLongOrNull() == song.id }
        val targetIndex = existingIndex ?: run {
            val currentPlayerIndex = player.currentMediaItemIndex.takeIf { it >= 0 } ?: 0
            val insertIndex = (currentPlayerIndex + 1).coerceIn(0, player.mediaItemCount)
            player.addMediaItem(insertIndex, AppPlayer.mediaItem(song, url, source))
            insertIndex
        }

        player.seekTo(targetIndex, startPosition.coerceAtLeast(0))
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        player.playWhenReady = true
        return true
    }

    private fun buildPlaybackQueue(song: Song, url: String, source: String): List<MediaItem> {
        val mediaItems = mutableListOf(AppPlayer.mediaItem(song, url, source))
        if (playQueue.isEmpty()) return mediaItems

        val startIndex = playQueue.indexOfFirst { it.id == song.id }.takeIf { it >= 0 } ?: currentIndex
        (1..PREPARED_QUEUE_WINDOW)
            .mapNotNull { offset -> playQueue.getOrNull((startIndex + offset) % playQueue.size) }
            .filter { it.id != song.id }
            .distinctBy { it.id }
            .mapNotNull { nextSong ->
                preparedQueueItems[nextSong.id]?.let { prepared ->
                    AppPlayer.mediaItem(prepared.song, prepared.url, prepared.source)
                }
            }
            .forEach(mediaItems::add)
        return mediaItems
    }

    private fun appendPreparedQueue(currentSongId: Long) {
        if (playQueue.size <= 1) return
        val startIndex = playQueue.indexOfFirst { it.id == currentSongId }.takeIf { it >= 0 } ?: currentIndex
        val existingIds = (0 until player.mediaItemCount)
            .mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
            .toSet()
        (1..PREPARED_QUEUE_WINDOW)
            .mapNotNull { offset -> playQueue.getOrNull((startIndex + offset) % playQueue.size) }
            .filter { it.id != currentSongId && it.id !in existingIds }
            .distinctBy { it.id }
            .mapNotNull { song ->
                preparedQueueItems[song.id]?.let { prepared ->
                    AppPlayer.mediaItem(prepared.song, prepared.url, prepared.source)
                }
            }
            .forEach { mediaItem ->
                player.addMediaItem(mediaItem)
            }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (player.isPlaying) {
                val duration = player.duration.takeIf { it > 0 } ?: 1
                val position = player.currentPosition.coerceAtLeast(0)
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

    override fun onCleared() {
        playRequestJob?.cancel()
        progressJob?.cancel()
        queuePrefetchJob?.cancel()
        bufferingFallbackJob?.cancel()
        player.removeListener(playerListener)
        if (_state.value.songUrl.isNullOrBlank()) {
            AppPlayer.stopPlaybackService(app)
        }
        super.onCleared()
    }
}
