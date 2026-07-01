package com.ncm.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.ncm.app.NeteaseApp
import com.ncm.app.data.model.Song
import com.ncm.app.playback.AppPlayer
import kotlinx.coroutines.Job
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

    private val player = AppPlayer.player(app)

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.value = _state.value.copy(isLoading = true)
                Player.STATE_READY -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isPlaying = player.playWhenReady,
                        duration = player.duration.takeIf { it > 0 } ?: _state.value.duration,
                        error = null
                    )
                    startProgressUpdates()
                }
                Player.STATE_ENDED -> {
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

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(
                isLoading = false,
                isPlaying = false,
                error = "播放失败：${error.errorCodeName}"
            )
        }
    }

    init {
        AppPlayer.mediaSession(app)
        player.addListener(playerListener)
    }

    fun play(songId: Long) {
        val current = _state.value
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

                progressJob?.cancel()
                player.stop()
                player.clearMediaItems()
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
                repo.getSongUrl(songId, _state.value.quality.bitrate, song.fee).onSuccess { urlResp ->
                    if (!isCurrentSongRequest(requestToken, songId)) return@onSuccess
                    val url = urlResp.url
                    if (url.isNullOrBlank()) {
                        stopUnavailable(urlResp.error ?: "这首歌当前不可播放")
                    } else {
                        startPlayback(song, url, source = urlResp.source)
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
        if (current.currentSong?.id == songId && !current.songUrl.isNullOrBlank()) return
        play(songId)
    }

    fun togglePlay() {
        if (_state.value.songUrl.isNullOrBlank()) {
            _state.value.currentSong?.let { play(it.id) }
            return
        }

        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun playNext() {
        if (playQueue.isEmpty()) return
        currentIndex = (currentIndex + 1) % playQueue.size
        play(playQueue[currentIndex].id)
    }

    fun playPrev() {
        if (playQueue.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) playQueue.size - 1 else currentIndex - 1
        play(playQueue[currentIndex].id)
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
            _state.value = _state.value.copy(quality = quality, isLoading = true, error = null)
            repo.getSongUrl(songId, quality.bitrate, currentSong.fee).onSuccess { urlResp ->
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

    private fun startPlayback(song: Song, url: String, startPosition: Long = 0, source: String = "netease") {
        progressJob?.cancel()
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(AppPlayer.mediaItem(song, url))
        player.prepare()
        if (startPosition > 0) {
            player.seekTo(startPosition)
        }
        player.playWhenReady = true
        _state.value = _state.value.copy(songUrl = url, audioSource = source, isLoading = true, error = null)
    }

    private fun stopUnavailable(message: String) {
        progressJob?.cancel()
        player.stop()
        player.clearMediaItems()
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

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (player.isPlaying) {
                val duration = player.duration.takeIf { it > 0 } ?: 1
                val position = player.currentPosition.coerceAtLeast(0)
                _state.value = _state.value.copy(
                    currentPosition = position,
                    duration = duration,
                    progress = position.toFloat() / duration.toFloat()
                )
                delay(100)
            }
        }
    }

    override fun onCleared() {
        playRequestJob?.cancel()
        progressJob?.cancel()
        player.removeListener(playerListener)
        super.onCleared()
    }
}
