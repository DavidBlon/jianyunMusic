package com.ncm.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ncm.app.NeteaseApp
import com.ncm.app.data.AppCache
import com.ncm.app.data.FavoriteOrderStore
import com.ncm.app.data.JianyunFavoriteStore
import com.ncm.app.data.PlaylistMutationResult
import com.ncm.app.data.UserPlaylistStore
import com.ncm.app.data.model.*
import com.ncm.app.data.repository.JianyunOfficialContent
import com.ncm.app.data.weekly.WeeklyCacheCleaner
import com.ncm.app.domain.weekly.GenerationKey
import com.ncm.app.domain.weekly.GenerateWeeklyRecommendationUseCase
import com.ncm.app.domain.weekly.WEEKLY_PLAYLIST_ID
import com.ncm.app.domain.weekly.WeeklyRecResult
import com.ncm.app.domain.weekly.WeeklyRecUiMapper
import com.ncm.app.domain.weekly.WeeklyRecUiState
import com.ncm.app.domain.weekly.canReuseWeeklyRecommendation
import com.ncm.app.domain.weekly.restoreWeeklyRecommendationOrder
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.OnlinePlaylist
import com.ncm.app.plugin.model.pluginSourceDisplayName
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecommendedPlaylistUi(
    val id: Long,
    val title: String,
    val artworkUrl: String?,
    val playCount: Long,
    val creator: String?
)

internal fun onlinePlaylistUiId(pluginId: String, remoteId: String): Long =
    -(10_000_000_000L + (("$pluginId#$remoteId".hashCode().toLong()) and 0xffff_ffffL))

data class DiscoverUiState(
    val recommendedPlaylists: List<RecommendedPlaylistUi> = emptyList(),
    val recommendedSongs: List<OnlineTrack> = emptyList(),
    val topLists: List<RecommendedPlaylistUi> = emptyList(),
    val recentTracks: List<OnlineTrack> = emptyList(),
    val recommendationSourceLabel: String? = null,
    val recommendationError: String? = null,
    val songRecommendationError: String? = null,
    val recommendationSourceId: String? = null,
    val recommendationsLoaded: Boolean = false,
    val localContentLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class PlaylistDetailUiState(
    val playlist: PlaylistMeta? = null,
    val songs: List<Song> = emptyList(),
    val pluginTracks: List<OnlineTrack> = emptyList(),
    val trackOrder: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val loadedPlaylistId: Long = 0,
    val isFullyLoaded: Boolean = false
)

data class ArtistDetailUiState(
    val artistId: Long = 0,
    val artist: ArtistDetail? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class SearchUiState(
    val query: String = "",
    val results: List<Song> = emptyList(),                 // 本地（简云官方）
    val pluginResults: List<OnlineTrack> = emptyList(),    // 当前来源插件结果（单来源，GC #6）
    val pluginSourceLabel: String? = null,
    val pluginError: String? = null,                       // 插件搜索失败原因（不静默切换，GC #6）
    val isSearching: Boolean = false,
    val isCommitted: Boolean = false,
    val history: List<String> = emptyList(),
    val isLinglanConfigured: Boolean = false
)

data class MyUiState(
    val playlists: List<Playlist> = emptyList(),
    val likedCount: Int = 0,
    val isLoading: Boolean = true
)

const val LOCAL_FAVORITES_PLAYLIST_ID = -10_001L
const val RECENTLY_PLAYED_PLAYLIST_ID = -10_002L

class MainViewModel : ViewModel() {

    private val repo = NeteaseApp.instance.repository
    private val session = NeteaseApp.instance.session
    private val cache = NeteaseApp.instance.cache
    private val jianyunFavorites = JianyunFavoriteStore(cache) { session.userId }
    private val favoriteOrderStore = FavoriteOrderStore(cache) { session.userId }
    private val userPlaylistStore = UserPlaylistStore(cache, userId = { session.userId })
    private val onlineFavoriteStore = NeteaseApp.instance.onlineFavoriteStore
    private val onlinePlaybackHistoryStore = NeteaseApp.instance.onlinePlaybackHistoryStore
    private val onlineSourceSettings = NeteaseApp.instance.onlineSourceSettings

    private val _discoverState = MutableStateFlow(DiscoverUiState())
    val discoverState: StateFlow<DiscoverUiState> = _discoverState

    private val _playlistState = MutableStateFlow(PlaylistDetailUiState())
    val playlistState: StateFlow<PlaylistDetailUiState> = _playlistState
    private var durationEnrichmentJob: Job? = null
    private var onlinePlaylistDetailJob: Job? = null
    private var playlistDetailRequest = 0L

    private val _artistDetailState = MutableStateFlow(ArtistDetailUiState())
    val artistDetailState: StateFlow<ArtistDetailUiState> = _artistDetailState

    private val _searchState = MutableStateFlow(
        SearchUiState(
            history = loadSearchHistory(),
            isLinglanConfigured = onlineSourceSettings.currentPluginId
                ?.let { NeteaseApp.instance.pluginRuntime.providerFor(it) != null }
                ?: false
        )
    )
    val searchState: StateFlow<SearchUiState> = _searchState

    private val _myState = MutableStateFlow(MyUiState(isLoading = false))
    val myState: StateFlow<MyUiState> = _myState

    private val _weeklyRecState = MutableStateFlow<WeeklyRecUiState>(WeeklyRecUiState.Loading)
    val weeklyRecState: StateFlow<WeeklyRecUiState> = _weeklyRecState

    private val _weeklyDetailSongs = MutableStateFlow<List<Song>?>(null)
    val weeklyDetailSongs: StateFlow<List<Song>?> = _weeklyDetailSongs

    private val _weeklyDetailLoading = MutableStateFlow(false)
    val weeklyDetailLoading: StateFlow<Boolean> = _weeklyDetailLoading

    /** 已落定的结果、当前请求与已水合详情均按用户和周分隔，避免跨周复用旧内存。 */
    private var weeklySettledKey: GenerationKey? = null
    private var weeklyRequestedKey: GenerationKey? = null
    private var weeklyDetailSongsKey: GenerationKey? = null
    private var weeklyDetailLoadingKey: GenerationKey? = null

    private val generateWeeklyRecommendationUseCase: GenerateWeeklyRecommendationUseCase
        get() = NeteaseApp.instance.generateWeeklyRecommendationUseCase
    private val weeklyCacheCleaner: WeeklyCacheCleaner
        get() = NeteaseApp.instance.weeklyCacheCleaner

    private val playlistCache = mutableMapOf<Long, PlaylistDetailUiState>()
    private val onlinePlaylistByUiId = mutableMapOf<Long, OnlinePlaylist>()
    private val artistDetailCache = mutableMapOf<Long, ArtistDetail>()
    private var searchGeneration = 0

    // ---- 首页：本地内容 + 当前插件明确提供的推荐歌单能力 ----

    fun loadDiscover(force: Boolean = false) {
        val current = _discoverState.value
        val sourceId = onlineSourceSettings.currentPluginId
        if (
            !force &&
            current.localContentLoaded &&
            current.recommendationSourceId == sourceId &&
            current.recommendationsLoaded
        ) return
        if (current.isLoading) return

        viewModelScope.launch {
            _discoverState.value = current.copy(isLoading = true, error = null)
            val provider = sourceId?.let { NeteaseApp.instance.pluginRuntime.providerFor(it) }
            val recommendationResult = try {
                Result.success(
                    kotlinx.coroutines.withContext<List<OnlinePlaylist>>(
                        kotlinx.coroutines.Dispatchers.IO
                    ) {
                        when {
                            sourceId == null -> emptyList()
                            provider == null -> throw IllegalStateException("在线来源正在恢复，请稍后重试")
                            !provider.supportsRecommendedSheets() ->
                                throw IllegalStateException("当前音源暂不提供推荐歌单")
                            else -> provider.recommendedSheets(page = 1).items
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

            val onlinePlaylists = recommendationResult.getOrDefault(emptyList())
            val songRecommendationResult = when {
                sourceId == null -> Result.success(emptyList())
                provider == null -> Result.failure(IllegalStateException("在线来源正在恢复，请稍后重试"))
                onlinePlaylists.isEmpty() -> Result.failure(
                    recommendationResult.exceptionOrNull()
                        ?: IllegalStateException("当前音源暂时没有可用推荐")
                )
                else -> try {
                    val tracks = kotlinx.coroutines.withContext<List<OnlineTrack>>(
                        kotlinx.coroutines.Dispatchers.IO
                    ) {
                        if (!provider.supportsMusicSheet()) {
                            throw IllegalStateException("当前音源暂不提供推荐歌曲")
                        }
                        var selected = emptyList<OnlineTrack>()
                        for (playlist in onlinePlaylists.take(4)) {
                            val candidate = runCatching {
                                provider.musicSheetInfo(playlist, page = 1)
                            }.getOrDefault(emptyList())
                            if (candidate.isNotEmpty()) {
                                selected = candidate.take(12)
                                break
                            }
                        }
                        selected
                    }
                    if (tracks.isEmpty()) {
                        Result.failure(IllegalStateException("当前音源暂时没有可用推荐歌曲"))
                    } else {
                        Result.success(tracks)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
onlinePlaylistByUiId.clear()
            val recommended = onlinePlaylists.map { playlist ->
                val uiId = onlinePlaylistUiId(playlist.pluginId, playlist.remoteId)
                onlinePlaylistByUiId[uiId] = playlist
                RecommendedPlaylistUi(
                    id = uiId,
                    title = playlist.title,
                    artworkUrl = playlist.artworkUrl,
                    playCount = playlist.playCount,
                    creator = playlist.creator
                )
            }

            val topListResult = try {
                Result.success(
                    kotlinx.coroutines.withContext<List<Any>>(
                        kotlinx.coroutines.Dispatchers.IO
                    ) {
                        when {
                            sourceId == null -> emptyList()
                            provider == null -> throw IllegalStateException("在线来源正在恢复，请稍后重试")
                            !provider.supportsTopLists() -> emptyList()
                            else -> provider.topLists()
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            val topLists = topListResult.getOrDefault(emptyList())
                .mapNotNull { it as? OnlinePlaylist }
                .map { playlist ->
                    val uiId = onlinePlaylistUiId(playlist.pluginId, playlist.remoteId)
                    onlinePlaylistByUiId[uiId] = playlist
                    RecommendedPlaylistUi(
                        id = uiId,
                        title = playlist.title,
                        artworkUrl = playlist.artworkUrl,
                        playCount = playlist.playCount,
                        creator = playlist.creator
                    )
                }

            val recentTracks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { onlinePlaybackHistoryStore.load() }
                    .getOrDefault(emptyList())
                    .take(RECENT_TRACKS_LIMIT)
            }

            // 每周推荐为幂等 single-flight；无数据/失败时首页卡片自动隐藏。
            loadWeeklyRecommendation()

            _discoverState.value = DiscoverUiState(
                recommendedPlaylists = recommended,
                recommendedSongs = songRecommendationResult.getOrDefault(emptyList()),
                topLists = topLists,
                recentTracks = recentTracks,
                recommendationSourceLabel = sourceId?.let(::sourceLabel),
                recommendationError = when {
                    sourceId == null -> "连接在线音源后显示推荐歌单"
                    else -> recommendationResult.exceptionOrNull()?.message
                },
                songRecommendationError = when {
                    sourceId == null -> "连接在线音源后显示推荐歌曲"
                    else -> songRecommendationResult.exceptionOrNull()?.message
                },
                recommendationSourceId = sourceId,
                recommendationsLoaded = sourceId == null || provider != null,
                localContentLoaded = true,
                isLoading = false
            )
        }
    }

    fun refreshDiscover() = loadDiscover(force = true)

    /** 本地播放历史（PlayerViewModel 持久化在同一偏好，跨页面共享）。 */
    private fun loadPlayHistory(): List<Song> {
        val key = AppCache.KEY_PLAY_HISTORY_PREFIX + session.userId
        return cache.get<List<Song>>(key)
            .orEmpty()
            .filter { JianyunOfficialContent.isOfficialSongId(it.id) }
    }

    // ---- 歌单详情（每周推荐；在线歌单在插件能力接入前不可用）----

    fun loadPlaylistDetail(id: Long, force: Boolean = false) {
        playlistDetailRequest++
        onlinePlaylistDetailJob?.cancel()
        durationEnrichmentJob?.cancel()
        userPlaylistStore.content(id)?.let { content ->
            _playlistState.value = PlaylistDetailUiState(
                playlist = content.playlist,
                songs = content.songs,
                pluginTracks = content.onlineTracks,
                loadedPlaylistId = id,
                isFullyLoaded = true
            )
            enrichPlaylistDurations(id, content.onlineTracks)
            return
        }
        onlinePlaylistByUiId[id]?.let { playlist ->
            loadOnlinePlaylistDetail(id, playlist, force)
            return
        }
        if (id == LOCAL_FAVORITES_PLAYLIST_ID || id == RECENTLY_PLAYED_PLAYLIST_ID) {
            _playlistState.value = PlaylistDetailUiState(
                playlist = PlaylistMeta(
                    id = id,
                    name = if (id == LOCAL_FAVORITES_PLAYLIST_ID) "我喜欢的音乐" else "最近播放"
                ),
                isLoading = true,
                loadedPlaylistId = id,
            )
            viewModelScope.launch {
                val songs = if (id == LOCAL_FAVORITES_PLAYLIST_ID) {
                    jianyunFavorites.load()
                } else {
                    loadPlayHistory()
                }
                val pluginTracks = if (id == LOCAL_FAVORITES_PLAYLIST_ID) {
                    runCatching { onlineFavoriteStore.allFavorites() }.getOrDefault(emptyList())
                } else {
                    onlinePlaybackHistoryStore.load()
                }
                val trackOrder = if (id == LOCAL_FAVORITES_PLAYLIST_ID) {
                    favoriteOrderStore.orderedKeys(songs, pluginTracks)
                } else {
                    emptyList()
                }
                if (_playlistState.value.loadedPlaylistId != id) return@launch
                _playlistState.value = PlaylistDetailUiState(
                    playlist = PlaylistMeta(
                        id = id,
                        name = if (id == LOCAL_FAVORITES_PLAYLIST_ID) "我喜欢的音乐" else "最近播放",
                        cover = orderedFavoriteCover(songs, pluginTracks, trackOrder)
                            ?: songs.firstNotNullOfOrNull { it.album?.picUrl }
                            ?: pluginTracks.firstNotNullOfOrNull { it.artworkUrl ?: it.album?.artworkUrl },
                        trackCount = songs.size + pluginTracks.size
                    ),
                    songs = songs,
                    pluginTracks = pluginTracks,
                    trackOrder = trackOrder,
                    loadedPlaylistId = id,
                    isFullyLoaded = true
                )
                enrichPlaylistDurations(id, pluginTracks)
            }
            return
        }
        if (id == WEEKLY_PLAYLIST_ID) {
            loadWeeklyPlaylistDetail()
            return
        }
        // P6T2 后本地没有在线歌单数据源；旧网易云歌单条目保留展示身份但不保证可加载（spec §12）
        _playlistState.value = PlaylistDetailUiState(
            isLoading = false,
            error = "该歌单来自历史网易云数据，暂不可用",
            loadedPlaylistId = id
        )
    }

    fun clearPlaylistDetail() {
        playlistDetailRequest++
        onlinePlaylistDetailJob?.cancel()
        onlinePlaylistDetailJob = null
        durationEnrichmentJob?.cancel()
        durationEnrichmentJob = null
        val current = _playlistState.value
        if (current.isLoading) {
            playlistCache.remove(current.loadedPlaylistId)
        }
        _playlistState.value = PlaylistDetailUiState()
    }

    private fun loadOnlinePlaylistDetail(id: Long, playlist: OnlinePlaylist, force: Boolean) {
        if (!force) {
            playlistCache[id]?.takeIf { it.isFullyLoaded }?.let {
                _playlistState.value = it
                enrichPlaylistDurations(id, it.pluginTracks)
                return
            }
        }
        if (_playlistState.value.isLoading && _playlistState.value.loadedPlaylistId == id) return

        val initialMeta = PlaylistMeta(
            id = id,
            name = playlist.title,
            cover = playlist.artworkUrl
        )
        _playlistState.value = PlaylistDetailUiState(
            playlist = initialMeta,
            isLoading = true,
            loadedPlaylistId = id
        )
        val requestId = ++playlistDetailRequest
        onlinePlaylistDetailJob?.cancel()
        val job = viewModelScope.launch {
            val provider = NeteaseApp.instance.pluginRuntime.providerFor(playlist.pluginId)
            val result = try {
                kotlinx.coroutines.withContext<Result<List<OnlineTrack>>>(
                    kotlinx.coroutines.Dispatchers.IO
                ) {
                    when {
                        provider == null -> Result.failure(IllegalStateException("在线来源未加载，请返回后重试"))
                        !provider.supportsMusicSheet() -> Result.failure(IllegalStateException("当前音源暂不支持歌单详情"))
                        else -> Result.success(provider.musicSheetInfo(playlist, page = 1))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (_playlistState.value.loadedPlaylistId != id || requestId != playlistDetailRequest) {
                return@launch
            }
            val tracks = result.getOrDefault(emptyList())
            val finalState = PlaylistDetailUiState(
                playlist = initialMeta.copy(trackCount = tracks.size),
                pluginTracks = tracks,
                isLoading = false,
                error = result.exceptionOrNull()?.message
                    ?: if (tracks.isEmpty()) "该歌单暂时没有可播放歌曲" else null,
                loadedPlaylistId = id,
                isFullyLoaded = result.isSuccess
            )
            playlistCache[id] = finalState
            _playlistState.value = finalState
            enrichPlaylistDurations(id, tracks)
        }
        onlinePlaylistDetailJob = job
    }

    private fun enrichPlaylistDurations(id: Long, tracks: List<OnlineTrack>) {
        if (tracks.none {
                it.durationMs == null || it.durationMs <= 0L ||
                    (it.artworkUrl.isNullOrBlank() && it.album?.artworkUrl.isNullOrBlank())
            }
        ) return
        durationEnrichmentJob?.cancel()
        durationEnrichmentJob = viewModelScope.launch {
            val enriched = withContext(Dispatchers.IO) {
                enrichMissingTrackDurations(tracks) { pluginId ->
                    NeteaseApp.instance.pluginRuntime.providerFor(pluginId)
                }
            }
            if (_playlistState.value.loadedPlaylistId != id || enriched == tracks) return@launch
            val updated = _playlistState.value.copy(pluginTracks = enriched)
            _playlistState.value = updated
            if (playlistCache.containsKey(id)) playlistCache[id] = updated
        }
    }

    /** 每周推荐以标准歌单详情呈现（网易云相似歌曲接口移除后无数据源，保留空态）。 */
    private fun loadWeeklyPlaylistDetail() {
        if (_playlistState.value.isLoading && _playlistState.value.loadedPlaylistId == WEEKLY_PLAYLIST_ID) return

        _playlistState.value = PlaylistDetailUiState(
            playlist = PlaylistMeta(id = WEEKLY_PLAYLIST_ID, name = "每周推荐"),
            isLoading = true,
            loadedPlaylistId = WEEKLY_PLAYLIST_ID
        )
        viewModelScope.launch {
            loadWeeklyRecommendation()?.join()
            if (_playlistState.value.loadedPlaylistId != WEEKLY_PLAYLIST_ID) return@launch

            val rec = _weeklyRecState.value
            val count = (rec as? WeeklyRecUiState.Success)?.songs?.size ?: 0
            val meta = PlaylistMeta(
                id = WEEKLY_PLAYLIST_ID,
                name = "每周推荐",
                trackCount = count
            )
            val songs = hydrateWeeklyDetailSongsNow().orEmpty()
            if (_playlistState.value.loadedPlaylistId != WEEKLY_PLAYLIST_ID) return@launch
            val error = when (rec) {
                is WeeklyRecUiState.InsufficientData -> "本周听歌数据不足，多听几首下周再来"
                is WeeklyRecUiState.Error -> rec.message
                is WeeklyRecUiState.Success -> if (songs.isEmpty()) "歌曲加载失败，请重试" else null
                WeeklyRecUiState.Loading -> null
            }
            _playlistState.value = PlaylistDetailUiState(
                playlist = meta,
                songs = songs,
                isLoading = false,
                error = error,
                loadedPlaylistId = WEEKLY_PLAYLIST_ID,
                isFullyLoaded = true
            )
        }
    }

    fun loadArtistDetail(id: Long, force: Boolean = false) {
        if (id <= 0) {
            _artistDetailState.value = ArtistDetailUiState(
                artistId = id,
                error = "歌手信息不可用"
            )
            return
        }
        if (!force && id != JianyunOfficialContent.ARTIST_ID) {
            artistDetailCache[id]?.let { cached ->
                _artistDetailState.value = ArtistDetailUiState(
                    artistId = id,
                    artist = cached
                )
                return
            }
        }
        if (_artistDetailState.value.isLoading && _artistDetailState.value.artistId == id) return

        viewModelScope.launch {
            _artistDetailState.value = ArtistDetailUiState(
                artistId = id,
                artist = artistDetailCache[id],
                isLoading = true
            )
            repo.getArtistDetail(id).onSuccess { artist ->
                artistDetailCache[id] = artist
                if (_artistDetailState.value.artistId == id) {
                    _artistDetailState.value = ArtistDetailUiState(
                        artistId = id,
                        artist = artist
                    )
                }
            }.onFailure { error ->
                if (_artistDetailState.value.artistId == id) {
                    _artistDetailState.value = _artistDetailState.value.copy(
                        isLoading = false,
                        error = error.message ?: "歌手信息加载失败"
                    )
                }
            }
        }
    }

    private fun PlaylistDetailUiState.isCompleteEnough(): Boolean {
        return loadedPlaylistId > 0 && playlist != null && songs.isNotEmpty() && isFullyLoaded
    }

    fun onLikedSongChanged(song: Song, liked: Boolean) {
        if (JianyunOfficialContent.isOfficialSongId(song.id)) {
            jianyunFavorites.update(song, liked)
            favoriteOrderStore.updateLocal(song.id, liked)
            refreshDiscover()
        }
        if (_playlistState.value.loadedPlaylistId == LOCAL_FAVORITES_PLAYLIST_ID) {
            loadPlaylistDetail(LOCAL_FAVORITES_PLAYLIST_ID, force = true)
        }
        loadMyData(force = true)
    }

    // ---- 搜索（本地 + 当前插件来源，单来源不兜底）----

    fun search(keywords: String, force: Boolean = false, committed: Boolean = false) {
        val trimmed = keywords.trim()
        if (trimmed.isBlank()) {
            clearSearch()
            return
        }
        val currentSearch = _searchState.value
        // Ignore repeated taps/IME submissions while this exact committed request is running.
        if (committed && currentSearch.isCommitted && currentSearch.isSearching && trimmed == currentSearch.query) return
        // An explicit submission (keyboard/history/suggestion) starts a committed search.
        // SearchScreen's query debounce fires shortly afterwards with the same value; do
        // not let that non-committed request replace the committed state and its result.
        if (!force && !committed && trimmed == currentSearch.query && currentSearch.isCommitted) return
        if (!force && trimmed == currentSearch.query && currentSearch.results.isNotEmpty()) return

        val generation = ++searchGeneration
        viewModelScope.launch {
            _searchState.value = _searchState.value.copy(
                query = trimmed,
                results = emptyList(),
                pluginResults = emptyList(),
                pluginSourceLabel = null,
                pluginError = null,
                isSearching = true,
                isCommitted = committed
            )
            val sourceId = onlineSourceSettings.currentPluginId
            val sourceReady = sourceId?.let { NeteaseApp.instance.pluginRuntime.providerFor(it) != null } == true
            val requestPlan = searchRequestPlan(committed = committed, sourceReady = sourceReady)
            _searchState.value = _searchState.value.copy(isLinglanConfigured = sourceReady)
            // 插件搜索在 JS 引擎中阻塞执行，必须在 IO 线程跑，避免卡主线程
            val localDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                repo.search(trimmed).getOrDefault(SearchResponse())
            }
            val pluginDeferred = if (requestPlan.searchOnline) {
                async(kotlinx.coroutines.Dispatchers.IO) {
                    repo.searchFromPlugin(trimmed, page = 1, type = "music")
                }
            } else {
                null
            }
            val local = localDeferred.await()
            val plugin = pluginDeferred?.await()
            if (generation == searchGeneration && _searchState.value.query == trimmed) {
                _searchState.value = _searchState.value.copy(
                    results = local.songs,
                    pluginResults = plugin?.getOrNull()?.items.orEmpty(),
                    pluginSourceLabel = sourceId?.takeIf { sourceReady }?.let(::sourceLabel),
                    pluginError = when {
                        sourceId != null && !sourceReady -> "在线来源正在恢复，请稍后重试或到设置中刷新来源"
                        requestPlan.searchOnline && sourceId != null -> pluginSearchErrorMessage(plugin?.exceptionOrNull())
                        else -> null
                    },
                    isLinglanConfigured = sourceReady,
                    isSearching = false
                )
            }
        }
    }

    private fun sourceLabel(pluginId: String): String = pluginSourceDisplayName(pluginId)

    fun clearSearch() {
        searchGeneration++
        _searchState.value = _searchState.value.copy(
            query = "",
            results = emptyList(),
            pluginResults = emptyList(),
            pluginSourceLabel = null,
            pluginError = null,
            isSearching = false,
            isCommitted = false
        )
    }

    fun submitSearch(keywords: String) {
        val trimmed = keywords.trim()
        if (trimmed.isBlank()) return
        // Only explicit submissions become part of the user's search history.
        rememberSearchHistory(trimmed)
        search(trimmed, force = true, committed = true)
    }

    private fun rememberSearchHistory(keyword: String) {
        val history = (listOf(keyword) + _searchState.value.history.filterNot { it.equals(keyword, ignoreCase = true) })
            .take(12)
        cache.put(AppCache.KEY_SEARCH_HISTORY_PREFIX + session.userId, history)
        _searchState.value = _searchState.value.copy(history = history)
    }

    fun removeSearchHistory(keyword: String) {
        val history = _searchState.value.history.filterNot { it == keyword }
        cache.put(AppCache.KEY_SEARCH_HISTORY_PREFIX + session.userId, history)
        _searchState.value = _searchState.value.copy(history = history)
    }

    fun clearSearchHistory() {
        cache.remove(AppCache.KEY_SEARCH_HISTORY_PREFIX + session.userId)
        _searchState.value = _searchState.value.copy(history = emptyList())
    }

    private fun loadSearchHistory(): List<String> =
        cache.get<List<String>>(AppCache.KEY_SEARCH_HISTORY_PREFIX + session.userId).orEmpty()

    // ---- 每周推荐（网易云相似歌曲接口移除后为休眠状态，入口已隐藏）----

    fun loadWeeklyRecommendation(): Job? {
        val key = currentWeeklyGenerationKey()
        if (key == null) {
            _weeklyRecState.value = WeeklyRecUiState.Loading
            weeklySettledKey = null
            weeklyRequestedKey = null
            return null
        }

        if (canReuseWeeklyRecommendation(_weeklyRecState.value, weeklySettledKey, key)) {
            return null
        }

        weeklyRequestedKey = key
        if (weeklyDetailSongsKey != key) {
            _weeklyDetailSongs.value = null
            weeklyDetailSongsKey = null
        }
        _weeklyRecState.value = WeeklyRecUiState.Loading
        return viewModelScope.launch {
            val result = try {
                generateWeeklyRecommendationUseCase.execute(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WeeklyRecResult.Failure(e.message)
            }
            if (weeklyRequestedKey == key) {
                weeklySettledKey = key
                _weeklyRecState.value = WeeklyRecUiMapper.toUiState(result)
            }
        }
    }

    private fun currentWeeklyGenerationKey(): GenerationKey? {
        val userId = session.userId
        if (userId <= 0) return null
        val zoneId = ZoneId.systemDefault()
        return GenerationKey(
            userId = userId,
            displayWeekStart = LocalDate.now(zoneId)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        )
    }

    fun cleanupWeeklyCacheOnPageOpen() {
        val userId = session.userId
        if (userId <= 0) return
        viewModelScope.launch {
            weeklyCacheCleaner.cleanupOnPageOpen(userId)
        }
    }

    /** 详情页水合：返回整份歌单的完整 Song 列表（含封面）。已加载或加载中直接返回。 */
    suspend fun hydrateWeeklyDetailSongsNow(): List<Song>? {
        val key = weeklySettledKey ?: return null
        _weeklyDetailSongs.value
            ?.takeIf { it.isNotEmpty() && weeklyDetailSongsKey == key }
            ?.let { return it }
        if (_weeklyDetailLoading.value && weeklyDetailLoadingKey == key) return null
        _weeklyDetailLoading.value = true
        weeklyDetailLoadingKey = key
        return try {
            val state = _weeklyRecState.value
            val ids = (state as? WeeklyRecUiState.Success)?.songs?.map { it.songId }.orEmpty()
            if (ids.isEmpty()) {
                null
            } else {
                val loaded = repo.getSongDetail(ids).getOrNull().orEmpty()
                val ordered = restoreWeeklyRecommendationOrder(ids, loaded)
                if (ordered.isEmpty() || weeklySettledKey != key) {
                    null
                } else {
                    _weeklyDetailSongs.value = ordered
                    weeklyDetailSongsKey = key
                    ordered
                }
            }
        } finally {
            if (weeklyDetailLoadingKey == key) {
                _weeklyDetailLoading.value = false
                weeklyDetailLoadingKey = null
            }
        }
    }

    // ---- 本地资料库 ----

    fun createUserPlaylist(name: String): Long? {
        val playlistId = userPlaylistStore.create(name) ?: return null
        loadMyData(force = true)
        return playlistId
    }

    fun deleteUserPlaylist(playlistId: Long): Boolean {
        val deleted = userPlaylistStore.delete(playlistId)
        if (deleted) {
            playlistCache.remove(playlistId)
            loadMyData(force = true)
        }
        return deleted
    }

    fun addSongToUserPlaylist(playlistId: Long, song: Song): PlaylistMutationResult {
        val result = userPlaylistStore.addSong(playlistId, song)
        if (result == PlaylistMutationResult.ADDED) refreshUserPlaylist(playlistId)
        return result
    }

    fun addOnlineTrackToUserPlaylist(playlistId: Long, track: OnlineTrack): PlaylistMutationResult {
        val result = userPlaylistStore.addOnlineTrack(playlistId, track)
        if (result == PlaylistMutationResult.ADDED) refreshUserPlaylist(playlistId)
        return result
    }

    fun removeSongFromUserPlaylist(playlistId: Long, songId: Long): Boolean {
        val removed = userPlaylistStore.removeSong(playlistId, songId)
        if (removed) refreshUserPlaylist(playlistId)
        return removed
    }

    fun removeOnlineTrackFromUserPlaylist(playlistId: Long, track: OnlineTrack): Boolean {
        val removed = userPlaylistStore.removeOnlineTrack(playlistId, track)
        if (removed) refreshUserPlaylist(playlistId)
        return removed
    }

    private fun refreshUserPlaylist(playlistId: Long) {
        playlistCache.remove(playlistId)
        if (_playlistState.value.loadedPlaylistId == playlistId) {
            loadPlaylistDetail(playlistId, force = true)
        }
        loadMyData(force = true)
    }

    fun loadMyData(force: Boolean = false) {
        if (!force && !_myState.value.isLoading && _myState.value.playlists.isNotEmpty()) return

        viewModelScope.launch {
            _myState.value = _myState.value.copy(isLoading = true)
            val likedSongs = jianyunFavorites.load()
            val onlineLikedSongs = runCatching { onlineFavoriteStore.allFavorites() }.getOrDefault(emptyList())
            val favoriteOrder = favoriteOrderStore.orderedKeys(likedSongs, onlineLikedSongs)
            val recentSongs = loadPlayHistory()
            val onlineRecentSongs = onlinePlaybackHistoryStore.load()
            _myState.value = MyUiState(
                playlists = listOf(
                    Playlist(
                        id = LOCAL_FAVORITES_PLAYLIST_ID,
                        name = "我喜欢的音乐",
                        cover = orderedFavoriteCover(likedSongs, onlineLikedSongs, favoriteOrder)
                            ?: likedSongs.firstNotNullOfOrNull { it.album?.picUrl }
                            ?: onlineLikedSongs.firstNotNullOfOrNull { it.artworkUrl ?: it.album?.artworkUrl },
                        trackCount = likedSongs.size + onlineLikedSongs.size
                    ),
                    Playlist(
                        id = RECENTLY_PLAYED_PLAYLIST_ID,
                        name = "最近播放",
                        cover = recentSongs.firstNotNullOfOrNull { it.album?.picUrl }
                            ?: onlineRecentSongs.firstNotNullOfOrNull { it.artworkUrl ?: it.album?.artworkUrl },
                        trackCount = recentSongs.size + onlineRecentSongs.size
                    )
                ) + userPlaylistStore.summaries(),
                likedCount = likedSongs.size + onlineLikedSongs.size,
                isLoading = false
            )
        }
    }

private fun orderedFavoriteCover(
        songs: List<Song>,
        tracks: List<OnlineTrack>,
        order: List<String>
    ): String? {
        val localCovers = songs.associate { FavoriteOrderStore.localKey(it.id) to it.album?.picUrl }
        val onlineCovers = tracks.associate {
            FavoriteOrderStore.onlineKey(it.key) to (it.artworkUrl ?: it.album?.artworkUrl)
        }
        return order.firstNotNullOfOrNull { key -> localCovers[key] ?: onlineCovers[key] }
    }

    private companion object {
        const val RECENT_TRACKS_LIMIT = 10
    }
}
