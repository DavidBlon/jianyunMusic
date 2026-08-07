package com.ncm.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ncm.app.NeteaseApp
import com.ncm.app.data.AppCache
import com.ncm.app.data.JianyunFavoriteStore
import com.ncm.app.data.model.*
import com.ncm.app.data.repository.JianyunOfficialContent
import com.ncm.app.data.repository.MusicSourceKeyValidationResult
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 本地首页（P6T3）：只展示本地最近播放、收藏和简云官方内容，不再有网易云推荐（spec §18）。 */
data class DiscoverUiState(
    val officialSongs: List<Song> = emptyList(),
    val recentSongs: List<Song> = emptyList(),
    val likedCount: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class PlaylistDetailUiState(
    val playlist: PlaylistMeta? = null,
    val songs: List<Song> = emptyList(),
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
    val isSearching: Boolean = false,
    val isCommitted: Boolean = false,
    val history: List<String> = emptyList(),
    val isLinglanConfigured: Boolean = false
)

data class MyUiState(
    val profile: UserProfile? = null,
    val likedCount: Int = 0,
    val isLoading: Boolean = true
)

data class AppUiState(val isLoggedIn: Boolean = false)

class MainViewModel : ViewModel() {

    private val repo = NeteaseApp.instance.repository
    private val session = NeteaseApp.instance.session
    private val cache = NeteaseApp.instance.cache
    private val jianyunFavorites = JianyunFavoriteStore(cache) { session.userId }
    private val musicSourceSettings = NeteaseApp.instance.musicSourceSettings
    private val musicSourceKeyValidator = NeteaseApp.instance.musicSourceKeyValidator
    private val onlineSourceSettings = NeteaseApp.instance.onlineSourceSettings

    private val _discoverState = MutableStateFlow(DiscoverUiState())
    val discoverState: StateFlow<DiscoverUiState> = _discoverState

    private val _playlistState = MutableStateFlow(PlaylistDetailUiState())
    val playlistState: StateFlow<PlaylistDetailUiState> = _playlistState

    private val _artistDetailState = MutableStateFlow(ArtistDetailUiState())
    val artistDetailState: StateFlow<ArtistDetailUiState> = _artistDetailState

    private val _searchState = MutableStateFlow(
        SearchUiState(
            history = loadSearchHistory(),
            isLinglanConfigured = musicSourceSettings.cardKey.value.isNotBlank()
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

    private val _appState = MutableStateFlow(AppUiState(isLoggedIn = false))
    val appState: StateFlow<AppUiState> = _appState

    private val generateWeeklyRecommendationUseCase: GenerateWeeklyRecommendationUseCase
        get() = NeteaseApp.instance.generateWeeklyRecommendationUseCase
    private val weeklyCacheCleaner: WeeklyCacheCleaner
        get() = NeteaseApp.instance.weeklyCacheCleaner

    private val playlistCache = mutableMapOf<Long, PlaylistDetailUiState>()
    private val artistDetailCache = mutableMapOf<Long, ArtistDetail>()
    private var searchGeneration = 0

    init {
        viewModelScope.launch {
            musicSourceSettings.cardKey.collect { key ->
                _searchState.value = _searchState.value.copy(
                    isLinglanConfigured = key.isNotBlank()
                )
            }
        }
    }

    fun currentProfile(): UserProfile? = session.profile

    suspend fun validateAndSaveMusicSourceKey(
        key: String
    ): MusicSourceKeyValidationResult {
        val result = musicSourceKeyValidator.validate(key)
        if (result is MusicSourceKeyValidationResult.Valid) {
            musicSourceSettings.saveValidatedCardKey(key)
            musicSourceSettings.completeFirstUsePrompt()
            repo.onMusicSourceKeyChanged()
        }
        return result
    }

    fun skipFirstUseMusicSourcePrompt() {
        musicSourceSettings.completeFirstUsePrompt()
    }

    fun clearMusicSourceKey() {
        musicSourceSettings.clearCardKey()
        repo.onMusicSourceKeyChanged()
    }

    // ---- 本地首页（spec §18：本地最近播放/收藏/本地统计）----

    fun loadDiscover(force: Boolean = false) {
        val current = _discoverState.value
        if (!force && (current.officialSongs.isNotEmpty() || current.likedCount > 0)) return
        if (current.isLoading) return

        viewModelScope.launch {
            _discoverState.value = current.copy(isLoading = true, error = null)
            val official = repo.search("").getOrDefault(SearchResponse()).songs
            val history = loadPlayHistory().orEmpty()
            val recent = history.take(20)
            _discoverState.value = DiscoverUiState(
                officialSongs = official,
                recentSongs = recent,
                likedCount = jianyunFavorites.load().size,
                isLoading = false
            )
        }
    }

    fun refreshDiscover() = loadDiscover(force = true)

    /** 本地播放历史（PlayerViewModel 持久化在同一偏好，跨页面共享）。 */
    private fun loadPlayHistory(): List<Song> {
        val key = AppCache.KEY_PLAY_HISTORY_PREFIX + session.userId
        return cache.get<List<Song>>(key).orEmpty()
    }

    // ---- 歌单详情（每周推荐；在线歌单在插件能力接入前不可用）----

    fun loadPlaylistDetail(id: Long, force: Boolean = false) {
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
        // 本地收藏（简云官方）即时更新；legacy 网易云条目不支持点赞（spec §12）
        if (!JianyunOfficialContent.isOfficialSongId(song.id)) return
        jianyunFavorites.update(song, liked)
        _myState.value = _myState.value.copy(likedCount = jianyunFavorites.load().size)
        refreshDiscover()
    }

    // ---- 搜索（本地 + 当前插件来源，单来源不兜底）----

    fun search(keywords: String, force: Boolean = false, committed: Boolean = false) {
        val trimmed = keywords.trim()
        if (trimmed.isBlank()) {
            clearSearch()
            return
        }
        if (!force && trimmed == _searchState.value.query && _searchState.value.results.isNotEmpty()) return

        val generation = ++searchGeneration
        viewModelScope.launch {
            _searchState.value = _searchState.value.copy(
                query = trimmed,
                results = emptyList(),
                pluginResults = emptyList(),
                isSearching = true,
                isCommitted = committed
            )
            val sourceLabel = onlineSourceSettings.currentPluginId
            val localDeferred = async { repo.search(trimmed).getOrDefault(SearchResponse()) }
            val pluginDeferred = async {
                repo.searchFromPlugin(trimmed, page = 1, type = "music").getOrNull()
            }
            val local = localDeferred.await()
            val plugin = pluginDeferred.await()
            if (generation == searchGeneration && _searchState.value.query == trimmed) {
                _searchState.value = _searchState.value.copy(
                    results = local.songs,
                    pluginResults = plugin?.items.orEmpty(),
                    pluginSourceLabel = sourceLabel?.let(::sourceLabel),
                    isSearching = false
                )
            }
        }
    }

    private fun sourceLabel(pluginId: String): String = when (pluginId) {
        "linglan.kw" -> "酷我"
        "linglan.kg" -> "酷狗"
        "linglan.tx" -> "QQ音乐"
        "linglan.wy" -> "网易云"
        else -> pluginId
    }

    fun clearSearch() {
        searchGeneration++
        _searchState.value = _searchState.value.copy(
            query = "",
            results = emptyList(),
            pluginResults = emptyList(),
            pluginSourceLabel = null,
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

    fun loadMyData(force: Boolean = false) {
        if (!force && !_myState.value.isLoading && _myState.value.profile != null) return

        viewModelScope.launch {
            _myState.value = _myState.value.copy(isLoading = true)
            val likedCount = jianyunFavorites.load().size
            val profile = session.profile ?: UserProfile(
                userId = session.userId,
                nickname = "本地用户"
            )
            _myState.value = MyUiState(
                profile = profile,
                likedCount = likedCount,
                isLoading = false
            )
        }
    }
}
