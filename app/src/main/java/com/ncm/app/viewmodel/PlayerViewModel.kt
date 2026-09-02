package com.ncm.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.ncm.app.NeteaseApp
import com.ncm.app.data.AppCache
import com.ncm.app.data.FavoriteOrderStore
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
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.pluginSourceDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

internal fun PlayerUiState.forPendingTrackSwitch(
    song: Song?,
    source: String
): PlayerUiState = copy(
    currentSong = song,
    songUrl = null,
    audioSource = source,
    lyric = null,
    tlyric = null,
    duration = song?.dt ?: 0L,
    isPlaying = false,
    isLoading = true,
    currentPosition = 0,
    progress = 0f,
    isLiked = false,
    isLikeUpdating = false,
    error = null
)

data class LinglanCacheUiState(
    val songCount: Int = 0,
    val sizeBytes: Long = 0,
    val isLoading: Boolean = false,
    val isClearing: Boolean = false,
    val message: String? = null
)

internal fun isPluginPlaybackSource(source: String): Boolean = source.startsWith("plugin:")

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
        private const val MAX_CONSECUTIVE_PLAYBACK_FAILURES = 3
        private const val SKIP_AFTER_FAILURE_DELAY_MS = 500L
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
    private val favoriteOrderStore = FavoriteOrderStore(app.cache) { session.userId }
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
    private var pendingPluginTrack: OnlineTrack? = null
    private var consecutivePlaybackFailures = 0
    private var retriedResolveSongKey: String? = null
    private val pluginPlaybackQueue = PluginPlaybackQueue()
    private val playlistPlaybackQueue = PlaylistPlaybackQueue()
    private val preparedQueueItems = mutableMapOf<Long, PreparedQueueItem>()
    private val notificationNavigationOwner = Any()

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
            pendingPluginTrack = null
            if (playlistPlaybackQueue.selectLocal(songId)) {
                syncPlaylistQueueState()
            }
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
            // 传输阶段失败（HTTP 状态码/网络 IO/超时）可自动恢复：先重解析（URL 可能过期），
            // 仍失败则参照 MusicFree 的行为自动跳到下一首；解码类错误只提示。
            if (!isAutoRecoverableError(error)) {
                showPlaybackError(error)
                return
            }
            handleAutoRecoverableFailure()
        }
    }

    init {
        AppPlayer.registerNotificationNavigation(
            owner = notificationNavigationOwner,
            onPrevious = ::playPrev,
            onNext = ::playNext
        )
        AppPlayer.mediaSession(app)
        player.addListener(playerListener)
        _state.value = _state.value.copy(history = loadHistory())
        restoreFromActivePlayer()
        refreshLinglanCacheStats()
    }

fun play(songId: Long) {
        pendingPluginTrack = null
        consecutivePlaybackFailures = 0
        retriedResolveSongKey = null
        if (playlistPlaybackQueue.selectLocal(songId)) {
            syncPlaylistQueueState()
        }
        val current = _state.value
        if (hasActiveMediaFor(songId)) {
            if (!player.isPlaying) player.play()
            restoreFromActivePlayer()
            return
        }
        if (current.currentSong?.id == songId && !current.songUrl.isNullOrBlank()) {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            if (!player.isPlaying) player.play()
            return
        }

        val requestToken = ++playRequestToken
        playRequestJob?.cancel()
        pauseCurrentPlaybackForTrackSwitch()
        val queuedSong = playQueue.firstOrNull { it.id == songId }
        _state.value = _state.value.forPendingTrackSwitch(queuedSong, PlaybackSource.NETEASE)
        playRequestJob = viewModelScope.launch {
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
fun playPluginTrack(track: OnlineTrack): Long {
        consecutivePlaybackFailures = 0
        retriedResolveSongKey = null
        if (playlistPlaybackQueue.isActive) {
            playlistPlaybackQueue.selectOnline(track.key)
            syncPlaylistQueueState()
        } else {
            if (!pluginPlaybackQueue.select(track)) {
                pluginPlaybackQueue.set(listOf(track))
            }
            syncPluginQueueState()
        }
        val shellSong = pluginShellSong(track)
        val source = "plugin:${track.key.pluginId}"
        pendingPluginTrack = track
        val requestToken = ++playRequestToken
        playRequestJob?.cancel()
        pauseCurrentPlaybackForTrackSwitch()
        _state.value = _state.value.forPendingTrackSwitch(shellSong, source)
        refreshPluginLiked(track)
        playRequestJob = viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                app.playbackResolver.resolveTrack(track, pluginQualityLabel(_state.value.quality))
            }
            resolved.onSuccess { playback ->
                if (!isCurrentSongRequest(requestToken, shellSong.id)) return@onSuccess
                val playedTrack = playback.track
                val playedShellSong = pluginShellSong(playedTrack)
                val playedSource = "plugin:${playedTrack.key.pluginId}"
                val media = playback.media
                pendingPluginTrack = playedTrack
                if (playlistPlaybackQueue.isActive) {
                    playlistPlaybackQueue.replaceSelected(track, playedTrack)
                    syncPlaylistQueueState()
                } else {
                    pluginPlaybackQueue.replaceSelected(track, playedTrack)
                    syncPluginQueueState()
                }
val mediaItem = AppPlayer.pluginMediaItem(
                    playedTrack,
                    playedShellSong,
                    media.url,
                    effectivePluginHeaders(media),
                    playedSource
                )
                player.stop()
                player.setMediaItems(listOf(mediaItem))
                player.prepare()
                player.play()
                AppPlayer.startPlaybackService(app)
                AppPlayer.updateCurrentPlayback(playedShellSong, playedSource)
                AppPlayer.beginPlaybackSession(playedShellSong)
                AppPlayer.refreshPlaybackNotification(app)
                _state.value = _state.value.copy(
                    currentSong = playedShellSong,
                    songUrl = media.url,
                    audioSource = playedSource,
                    duration = playedShellSong.dt,
                    isPlaying = true,
                    isLoading = false,
                    error = if (playback.usedFallback) {
                        "原音源不可用，已切换至${pluginSourceDisplayName(playedTrack.key.pluginId)}"
                    } else {
                        null
                    }
                )
                refreshPluginLiked(playedTrack)
                app.onlinePlaybackHistoryStore.remember(playedTrack)
                loadPluginLyric(playedTrack)
            }.onFailure { e ->
                if (!isCurrentSongRequest(requestToken, shellSong.id)) return@onFailure
                _state.value = _state.value.copy(isLoading = false, isPlaying = false, error = e.message)
            }
        }
        return shellSong.id
    }

    private fun pluginShellSong(track: OnlineTrack): Song = Song(
        id = pluginShellSongId(track.key),
        name = track.title,
        artists = track.artists.map { ArtistBrief(0L, it.name) },
        album = track.album?.let { AlbumBrief(0L, it.name, it.artworkUrl ?: track.artworkUrl) }
            ?: track.artworkUrl?.let { AlbumBrief(0L, "", it) },
        dt = track.durationMs ?: 0L
    )

    private fun syncPluginQueueState() {
        playQueue = pluginPlaybackQueue.tracks.map(::pluginShellSong)
        currentIndex = pluginPlaybackQueue.selectedIndex
            .coerceIn(0, (playQueue.size - 1).coerceAtLeast(0))
        _state.value = _state.value.copy(queue = playQueue)
    }

    private fun syncPlaylistQueueState() {
        playQueue = playlistPlaybackQueue.entries.map { entry ->
            when (entry) {
                is PlaylistPlaybackEntry.Local -> entry.song
                is PlaylistPlaybackEntry.Online -> pluginShellSong(entry.track)
            }
        }
        currentIndex = playlistPlaybackQueue.selectedIndex
            .coerceIn(0, (playQueue.size - 1).coerceAtLeast(0))
        _state.value = _state.value.copy(queue = playQueue)
    }

    private fun playPlaylistEntry(entry: PlaylistPlaybackEntry) {
        when (entry) {
            is PlaylistPlaybackEntry.Local -> {
                playlistPlaybackQueue.selectLocal(entry.song.id)
                syncPlaylistQueueState()
                play(entry.song.id)
            }
            is PlaylistPlaybackEntry.Online -> playPluginTrack(entry.track)
        }
    }

    private fun pluginQualityLabel(quality: PlaybackQuality): String? = when (quality) {
        PlaybackQuality.STANDARD -> "128k"
        PlaybackQuality.HIGHER -> "standard"
        PlaybackQuality.EXTREME -> "320k"
        PlaybackQuality.LOSSLESS -> "high"
    }

    fun open(songId: Long) {
        val current = _state.value
        if (current.currentSong?.id == songId && isPluginPlaybackSource(current.audioSource)) return
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
        if (playlistPlaybackQueue.isActive) {
            playlistPlaybackQueue.next()?.let { entry ->
                syncPlaylistQueueState()
                playPlaylistEntry(entry)
                return
            }
        }
        if (isPluginPlaybackSource(_state.value.audioSource)) {
            pluginPlaybackQueue.next()?.let { track ->
                syncPluginQueueState()
                playPluginTrack(track)
                return
            }
        }
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
        if (playlistPlaybackQueue.isActive) {
            playlistPlaybackQueue.previous()?.let { entry ->
                syncPlaylistQueueState()
                playPlaylistEntry(entry)
                return
            }
        }
        if (isPluginPlaybackSource(_state.value.audioSource)) {
            pluginPlaybackQueue.previous()?.let { track ->
                syncPluginQueueState()
                playPluginTrack(track)
                return
            }
        }
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
        playlistPlaybackQueue.clear()
        pluginPlaybackQueue.set(emptyList())
        playQueue = songs
        currentIndex = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        _state.value = _state.value.copy(queue = playQueue)
    }

    fun setPluginQueue(tracks: List<OnlineTrack>, startIndex: Int = 0) {
        playlistPlaybackQueue.clear()
        pluginPlaybackQueue.set(tracks, startIndex)
        preparedQueueItems.clear()
        syncPluginQueueState()
    }

    fun setPlaylistQueue(
        songs: List<Song>,
        onlineTracks: List<OnlineTrack>,
        order: List<String>,
        startSongId: Long
    ) {
        setPlaylistQueue(
            songs = songs,
            onlineTracks = onlineTracks,
            order = order,
            selectedKey = FavoriteOrderStore.localKey(startSongId)
        )
    }

    fun setPlaylistQueue(
        songs: List<Song>,
        onlineTracks: List<OnlineTrack>,
        order: List<String>,
        startTrack: OnlineTrack
    ) {
        setPlaylistQueue(
            songs = songs,
            onlineTracks = onlineTracks,
            order = order,
            selectedKey = FavoriteOrderStore.onlineKey(startTrack.key)
        )
    }

    private fun setPlaylistQueue(
        songs: List<Song>,
        onlineTracks: List<OnlineTrack>,
        order: List<String>,
        selectedKey: String
    ) {
        pluginPlaybackQueue.set(emptyList())
        preparedQueueItems.clear()
        playlistPlaybackQueue.set(songs, onlineTracks, order, selectedKey)
        keepOnlyCurrentMediaItem()
        syncPlaylistQueueState()
    }

    fun playFromQueue(songId: Long) {
        val playlistEntry = playlistPlaybackQueue.entryForLocalSong(songId)
            ?: playlistPlaybackQueue.entryForOnlineSongId(songId)
        if (playlistEntry != null) {
            playPlaylistEntry(playlistEntry)
            return
        }
        pluginPlaybackQueue.trackForSongId(songId)?.let { track ->
            pluginPlaybackQueue.select(track)
            syncPluginQueueState()
            playPluginTrack(track)
            return
        }
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
        if (playlistPlaybackQueue.isActive) {
            val entry = playlistPlaybackQueue.entryForLocalSong(songId)
                ?: playlistPlaybackQueue.entryForOnlineSongId(songId)
                ?: return
            playlistPlaybackQueue.remove(entry.stableKey)
            syncPlaylistQueueState()
            return
        }
        if (pluginPlaybackQueue.trackForSongId(songId) != null) {
            val selectedKey = pluginPlaybackQueue.current()?.key
            val remaining = pluginPlaybackQueue.tracks.filterNot {
                pluginShellSongId(it.key) == songId
            }
            pluginPlaybackQueue.set(
                remaining,
                remaining.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
            )
            syncPluginQueueState()
            return
        }
        playQueue = playQueue.filterNot { it.id == songId }
        if (index < currentIndex) currentIndex--
        currentIndex = currentIndex.coerceIn(0, (playQueue.size - 1).coerceAtLeast(0))
        preparedQueueItems.remove(songId)
        _state.value = _state.value.copy(queue = playQueue)
    }

    fun clearQueue() {
        if (playlistPlaybackQueue.isActive) {
            playlistPlaybackQueue.retainCurrent()
            preparedQueueItems.clear()
            syncPlaylistQueueState()
            return
        }
        if (isPluginPlaybackSource(_state.value.audioSource)) {
            val currentTrack = pluginPlaybackQueue.current() ?: pendingPluginTrack
            pluginPlaybackQueue.set(currentTrack?.let(::listOf).orEmpty())
            preparedQueueItems.clear()
            syncPluginQueueState()
            return
        }
        val current = _state.value.currentSong
        playQueue = current?.let(::listOf).orEmpty()
        currentIndex = 0
        preparedQueueItems.keys.retainAll(playQueue.map { it.id }.toSet())
        _state.value = _state.value.copy(queue = playQueue)
    }

    fun enqueueNext(song: Song) {
        if (playlistPlaybackQueue.isActive) {
            playlistPlaybackQueue.enqueueNext(PlaylistPlaybackEntry.Local(song))
            syncPlaylistQueueState()
            return
        }
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
        currentPluginTrackForState()?.let { track ->
            viewModelScope.launch {
                runCatching { app.onlineFavoriteStore.setLiked(track, targetLiked) }
                    .onSuccess {
                        favoriteOrderStore.updateOnline(track.key, targetLiked)
                        if (currentPluginTrackForState()?.key == track.key) {
                            _state.value = _state.value.copy(
                                isLiked = targetLiked,
                                isLikeUpdating = false
                            )
                        }
                        onChanged(song, targetLiked)
                    }
                    .onFailure { error ->
                        if (currentPluginTrackForState()?.key == track.key) {
                            _state.value = _state.value.copy(
                                isLiked = !targetLiked,
                                isLikeUpdating = false,
                                error = "收藏失败：${error.message ?: "无法写入本地收藏"}"
                            )
                        }
                    }
            }
            return
        }
        if (JianyunOfficialContent.isOfficialSongId(songId)) {
            jianyunFavorites.update(song, targetLiked)
            favoriteOrderStore.updateLocal(song.id, targetLiked)
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

    private fun isAutoRecoverableError(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> true
        else -> false
    }

    /** 可恢复播放失败：插件来源先重解析一次（URL 过期场景），仍失败则自动跳下一首（MusicFree 行为）。 */
    private fun handleAutoRecoverableFailure() {
        val current = _state.value
        val song = current.currentSong ?: return
        val isPlugin = isPluginPlaybackSource(current.audioSource)
        val failureKey = if (isPlugin) {
            (AppPlayer.currentPluginTrack() ?: pendingPluginTrack)?.key?.asComposite() ?: return
        } else {
            song.id.toString()
        }

        if (consecutivePlaybackFailures >= MAX_CONSECUTIVE_PLAYBACK_FAILURES) {
            stopAfterRepeatedFailures()
            return
        }
        if (isPlugin && failureKey != retriedResolveSongKey) {
            retriedResolveSongKey = failureKey
            retryResolveCurrentPluginTrack(current)
            return
        }
        consecutivePlaybackFailures++
        skipFailedSong()
    }

    /** 插件来源传输失败时重新解析（新 URL + 内置换源 fallback），成功后重建播放。 */
    private fun retryResolveCurrentPluginTrack(state: PlayerUiState) {
        val track = AppPlayer.currentPluginTrack() ?: pendingPluginTrack ?: return
        val shellSong = pluginShellSong(track)
        val requestToken = ++playRequestToken
        playRequestJob?.cancel()
        bufferingFallbackJob?.cancel()
        _state.value = state.copy(isLoading = true, error = null)
        playRequestJob = viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                app.playbackResolver.resolveTrack(track, pluginQualityLabel(state.quality))
            }
            resolved.onSuccess { playback ->
                if (!isCurrentSongRequest(requestToken, shellSong.id)) return@onSuccess
                val playedTrack = playback.track
                val playedShellSong = pluginShellSong(playedTrack)
                val playedSource = "plugin:${playedTrack.key.pluginId}"
                val media = playback.media
                pendingPluginTrack = playedTrack
                if (playlistPlaybackQueue.isActive) {
                    playlistPlaybackQueue.replaceSelected(track, playedTrack)
                    syncPlaylistQueueState()
                } else {
                    pluginPlaybackQueue.replaceSelected(track, playedTrack)
                    syncPluginQueueState()
                }
val mediaItem = AppPlayer.pluginMediaItem(
                    playedTrack,
                    playedShellSong,
                    media.url,
                    effectivePluginHeaders(media),
                    playedSource
                )
                player.stop()
                player.setMediaItems(listOf(mediaItem))
                player.prepare()
                player.play()
                AppPlayer.startPlaybackService(app)
                AppPlayer.updateCurrentPlayback(playedShellSong, playedSource)
                AppPlayer.beginPlaybackSession(playedShellSong)
                AppPlayer.refreshPlaybackNotification(app)
                _state.value = _state.value.copy(
                    currentSong = playedShellSong,
                    songUrl = media.url,
                    audioSource = playedSource,
                    duration = playedShellSong.dt,
                    lyric = null,
                    tlyric = null,
                    isPlaying = true,
                    isLoading = false,
                    currentPosition = 0,
                    progress = 0f,
                    isLiked = false,
                    isLikeUpdating = false,
                    error = if (playback.usedFallback) {
                        "原音源不可用，已切换至${pluginSourceDisplayName(playedTrack.key.pluginId)}"
                    } else {
                        null
                    }
                )
                refreshPluginLiked(playedTrack)
                app.onlinePlaybackHistoryStore.remember(playedTrack)
                loadPluginLyric(playedTrack)
}.onFailure { e ->
                if (!isCurrentSongRequest(requestToken, shellSong.id)) return@onFailure
                consecutivePlaybackFailures++
                skipFailedSong()
            }
        }
    }

    /** 插件返回的 userAgent 优先于通用 UA 注入播放请求头（部分 CDN 依赖特定 UA）。 */
    private fun effectivePluginHeaders(media: com.ncm.app.plugin.model.ResolvedMedia): Map<String, String> {
        val ua = media.userAgent?.trim().orEmpty()
        if (ua.isBlank()) return media.headers
        if (media.headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) return media.headers
        return media.headers + ("User-Agent" to ua)
    }

    private fun hasSkippableNext(): Boolean {
        if (playlistPlaybackQueue.isActive) return playlistPlaybackQueue.entries.size > 1
        if (isPluginPlaybackSource(_state.value.audioSource)) return pluginPlaybackQueue.tracks.size > 1
        return playQueue.size > 1
    }

    /** MusicFree handlePlayFail 行为：reset 播放器 → 短暂延迟 → 自动跳下一首。 */
    private fun skipFailedSong() {
        bufferingFallbackJob?.cancel()
        progressJob?.cancel()
        _state.value = _state.value.copy(isLoading = false, isPlaying = false)
        if (!hasSkippableNext()) {
            stopUnavailable("当前歌曲播放失败：${_state.value.currentSong?.name ?: "未知歌曲"}，队列中没有可跳过的歌曲")
            return
        }
        player.stop()
        player.clearMediaItems()
        viewModelScope.launch {
            delay(SKIP_AFTER_FAILURE_DELAY_MS)
            playNext()
        }
    }

    private fun stopAfterRepeatedFailures() {
        consecutivePlaybackFailures = 0
        retriedResolveSongKey = null
        bufferingFallbackJob?.cancel()
        progressJob?.cancel()
        player.stop()
        player.clearMediaItems()
        AppPlayer.stopPlaybackService(app)
        _state.value = _state.value.copy(
            songUrl = null,
            isLoading = false,
            isPlaying = false,
            currentPosition = 0,
            progress = 0f,
            error = "连续多首歌曲播放失败，已自动停止"
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

    private fun currentPluginTrackForState(): OnlineTrack? {
        val current = _state.value
        if (!isPluginPlaybackSource(current.audioSource)) return null
        val track = AppPlayer.currentPluginTrack() ?: pendingPluginTrack ?: return null
        return track.takeIf { pluginShellSongId(it.key) == current.currentSong?.id }
    }

    /** Returns the source-owned track for the song currently shown on the player, when applicable. */
    fun currentOnlineTrack(): OnlineTrack? = currentPluginTrackForState()

    private fun refreshPluginLiked(track: OnlineTrack) {
        val shellSongId = pluginShellSongId(track.key)
        viewModelScope.launch {
            val liked = runCatching { app.onlineFavoriteStore.isLiked(track.key) }.getOrDefault(false)
            if (
                _state.value.currentSong?.id == shellSongId &&
                isPluginPlaybackSource(_state.value.audioSource) &&
                !_state.value.isLikeUpdating
            ) {
                _state.value = _state.value.copy(isLiked = liked, isLikeUpdating = false)
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
        pluginPlaybackQueue.trackForSongId(song.id)?.let { track ->
            pluginPlaybackQueue.select(track)
            syncPluginQueueState()
            playPluginTrack(track)
            return
        }
        clearTransientBackupIfLeaving(song.id)
        val current = _state.value
        if (current.currentSong?.id == song.id && !current.songUrl.isNullOrBlank()) {
            if (!player.isPlaying) player.play()
            return
        }

        val requestToken = ++playRequestToken
        playRequestJob?.cancel()
        pauseCurrentPlaybackForTrackSwitch()
        _state.value = _state.value.forPendingTrackSwitch(song, PlaybackSource.NETEASE)
        playRequestJob = viewModelScope.launch {
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
        val source = AppPlayer.sourceFor(player.currentMediaItem) ?: AppPlayer.currentSource()
        _state.value = _state.value.copy(
            currentSong = song,
            songUrl = url,
            audioSource = source,
            duration = player.duration.takeIf { it > 0 } ?: song.dt,
            isPlaying = player.isPlaying,
            isLoading = player.playbackState == Player.STATE_BUFFERING,
            currentPosition = player.currentPosition.coerceAtLeast(0),
            progress = player.duration.takeIf { it > 0 }?.let { duration ->
                player.currentPosition.coerceAtLeast(0).toFloat() / duration.toFloat()
            } ?: 0f,
            error = null
        )
        val pluginTrack = AppPlayer.currentPluginTrack().takeIf { isPluginPlaybackSource(source) }
        if (pluginTrack != null) {
            pendingPluginTrack = pluginTrack
            if (playlistPlaybackQueue.selectOnline(pluginTrack.key)) {
                syncPlaylistQueueState()
            }
            refreshPluginLiked(pluginTrack)
            app.onlinePlaybackHistoryStore.remember(pluginTrack)
        } else {
            pendingPluginTrack = null
            if (playlistPlaybackQueue.selectLocal(song.id)) {
                syncPlaylistQueueState()
            }
            refreshLiked(song.id)
            rememberInHistory(song)
        }
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

    /** Immediately freezes the old item before asynchronous resolution of the new one. */
    private fun pauseCurrentPlaybackForTrackSwitch() {
        bufferingFallbackJob?.cancel()
        progressJob?.cancel()
        if (player.isPlaying || player.playWhenReady) {
            player.pause()
        }
    }

    private fun keepOnlyCurrentMediaItem() {
        val activeIndex = player.currentMediaItemIndex
        if (activeIndex !in 0 until player.mediaItemCount) return
        for (index in player.mediaItemCount - 1 downTo activeIndex + 1) {
            player.removeMediaItem(index)
        }
        for (index in activeIndex - 1 downTo 0) {
            player.removeMediaItem(index)
        }
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
            reuseExistingQueue = !playlistPlaybackQueue.isActive &&
                playQueue.any { it.id == prepared.song.id }
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
        pendingPluginTrack = track
        if (playlistPlaybackQueue.selectOnline(track.key)) {
            syncPlaylistQueueState()
        }
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
            isLiked = false,
            isLikeUpdating = false,
            error = null
        )
        refreshPluginLiked(track)
        app.onlinePlaybackHistoryStore.remember(track)
        loadPluginLyric(track)
    }

    private fun prefetchQueueAfter(songId: Long) {
        if (playlistPlaybackQueue.isActive) return
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
        if (playlistPlaybackQueue.isActive) return mediaItems
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
        if (playlistPlaybackQueue.isActive) return
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
        AppPlayer.unregisterNotificationNavigation(notificationNavigationOwner)
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
