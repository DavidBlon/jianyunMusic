package com.ncm.app.viewmodel

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ncm.app.NeteaseApp
import com.ncm.app.data.AppCache
import com.ncm.app.data.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DiscoverUiState(
    val banners: List<BannerItem> = emptyList(),
    val playlists: List<PinnedPlaylist> = emptyList(),
    val dailySongs: List<Song> = emptyList(),
    val newSongs: List<Song> = emptyList(),
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

data class SearchUiState(
    val query: String = "",
    val results: List<Song> = emptyList(),
    val isSearching: Boolean = false
)

data class MyUiState(
    val profile: UserProfile? = null,
    val playlists: List<Playlist> = emptyList(),
    val isLoading: Boolean = true,
    val likedCount: Int = 0,
    val listenCount: Int = 0,
    val followCount: Int = 0
)

data class QuickListUiState(
    val title: String = "",
    val entries: List<QuickEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val loadedType: String = ""
)

data class LoginUiState(
    val qrImg: String? = null,
    val qrKey: String? = null,
    val qrCode: Int = 0,
    val isLoggingIn: Boolean = false,
    val error: String? = null
)

data class AppUiState(val isLoggedIn: Boolean = false)

class MainViewModel : ViewModel() {

    private val repo = NeteaseApp.instance.repository
    private val session = NeteaseApp.instance.session
    private val cache = NeteaseApp.instance.cache

    private val _discoverState = MutableStateFlow(DiscoverUiState())
    val discoverState: StateFlow<DiscoverUiState> = _discoverState

    private val _playlistState = MutableStateFlow(PlaylistDetailUiState())
    val playlistState: StateFlow<PlaylistDetailUiState> = _playlistState

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState

    private val _myState = MutableStateFlow(if (session.isLoggedIn) MyUiState() else MyUiState(isLoading = false))
    val myState: StateFlow<MyUiState> = _myState

    private val _quickListState = MutableStateFlow(QuickListUiState())
    val quickListState: StateFlow<QuickListUiState> = _quickListState

    private val _loginState = MutableStateFlow(LoginUiState())
    val loginState: StateFlow<LoginUiState> = _loginState

    private val _appState = MutableStateFlow(AppUiState(isLoggedIn = session.isLoggedIn))
    val appState: StateFlow<AppUiState> = _appState
    private var qrPollingJob: Job? = null

    private val playlistCache = mutableMapOf<Long, PlaylistDetailUiState>()
    private val quickListCache = mutableMapOf<String, QuickListUiState>()
    private var searchGeneration = 0

    fun currentProfile(): UserProfile? = session.profile

    fun loadDiscover(force: Boolean = false) {
        val current = _discoverState.value
        if (!force && (current.playlists.isNotEmpty() || current.dailySongs.isNotEmpty())) return
        if (current.isLoading) return

        viewModelScope.launch {
            _discoverState.value = current.copy(isLoading = true, error = null)
            repo.getDiscoverHome().onSuccess { home ->
                _discoverState.value = DiscoverUiState(
                    banners = home.banners,
                    playlists = home.playlists,
                    dailySongs = home.dailySongs,
                    newSongs = home.newSongs,
                    isLoading = false
                )
            }.onFailure { e ->
                _discoverState.value = _discoverState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun loadPlaylistDetail(id: Long, force: Boolean = false) {
        if (!force) {
            playlistCache[id]?.let {
                _playlistState.value = it
                syncLoadedPlaylistToMyState(it)
                if (it.isCompleteEnough()) return
            }
        }
        if (_playlistState.value.isLoading && _playlistState.value.loadedPlaylistId == id) return

        viewModelScope.launch {
            val cachedSongs = playlistCache[id]?.songs.orEmpty()
            _playlistState.value = PlaylistDetailUiState(
                playlist = playlistCache[id]?.playlist,
                songs = cachedSongs,
                isLoading = true,
                loadedPlaylistId = id,
                isFullyLoaded = false
            )
            repo.getPlaylistTracks(id, count = 120, complete = false).onSuccess { resp ->
                val firstState = PlaylistDetailUiState(
                    playlist = resp.playlist,
                    songs = resp.tracks,
                    isLoading = resp.playlist?.trackCount?.let { resp.tracks.size < it } == true,
                    loadedPlaylistId = id,
                    isFullyLoaded = resp.playlist?.trackCount?.let { resp.tracks.size >= it } ?: true
                )
                playlistCache[id] = firstState
                _playlistState.value = firstState
                syncLoadedPlaylistToMyState(firstState)

                if (!firstState.isFullyLoaded) {
                    repo.getPlaylistTracks(id, count = 100000, complete = true).onSuccess { fullResp ->
                        val fullState = PlaylistDetailUiState(
                            playlist = fullResp.playlist,
                            songs = fullResp.tracks,
                            isLoading = false,
                            loadedPlaylistId = id,
                            isFullyLoaded = true
                        )
                        playlistCache[id] = fullState
                        if (_playlistState.value.loadedPlaylistId == id) {
                            _playlistState.value = fullState
                        }
                        syncLoadedPlaylistToMyState(fullState)
                    }.onFailure {
                        val current = _playlistState.value
                        if (current.loadedPlaylistId == id) {
                            _playlistState.value = current.copy(isLoading = false)
                        }
                    }
                }
            }.onFailure { e ->
                _playlistState.value = PlaylistDetailUiState(isLoading = false, error = e.message, loadedPlaylistId = id)
            }
        }
    }

    private fun PlaylistDetailUiState.isCompleteEnough(): Boolean {
        return loadedPlaylistId > 0 && playlist != null && songs.isNotEmpty() && isFullyLoaded
    }

    private fun syncLoadedPlaylistToMyState(state: PlaylistDetailUiState) {
        val detail = state.playlist ?: return
        val current = _myState.value
        if (current.playlists.none { it.id == detail.id }) return

        val updatedPlaylists = current.playlists.map { playlist ->
            if (playlist.id != detail.id) {
                playlist
            } else {
                val resolvedTrackCount = maxOf(detail.trackCount, state.songs.size).takeIf { it > 0 } ?: playlist.trackCount
                playlist.copy(
                    name = detail.name.ifBlank { playlist.name },
                    cover = detail.cover.takeUnless { it.isNullOrBlank() } ?: playlist.cover,
                    trackCount = resolvedTrackCount
                )
            }
        }
        val updated = current.copy(
            playlists = updatedPlaylists,
            likedCount = updatedPlaylists.firstOrNull { it.isLikedPlaylistName() }?.trackCount ?: current.likedCount
        )
        _myState.value = updated
    }

    fun onLikedSongChanged(song: Song, liked: Boolean) {
        val current = _myState.value
        val likedPlaylist = current.playlists.firstOrNull { it.isLikedPlaylistName() } ?: return
        val likedPlaylistId = likedPlaylist.id

        val updatedPlaylists = current.playlists.map { playlist ->
            if (playlist.id != likedPlaylistId) {
                playlist
            } else {
                val nextCount = if (liked) playlist.trackCount + 1 else (playlist.trackCount - 1).coerceAtLeast(0)
                playlist.copy(trackCount = nextCount)
            }
        }
        val updatedMyState = current.copy(
            playlists = updatedPlaylists,
            likedCount = updatedPlaylists.firstOrNull { it.id == likedPlaylistId }?.trackCount ?: current.likedCount
        )
        _myState.value = updatedMyState

        playlistCache[likedPlaylistId]?.let { cached ->
            val nextSongs = if (liked) {
                if (cached.songs.any { it.id == song.id }) cached.songs else listOf(song) + cached.songs
            } else {
                cached.songs.filterNot { it.id == song.id }
            }
            val nextTrackCount = maxOf(nextSongs.size, updatedMyState.likedCount)
            val nextState = cached.copy(
                playlist = cached.playlist?.copy(trackCount = nextTrackCount),
                songs = nextSongs
            )
            playlistCache[likedPlaylistId] = nextState
            if (_playlistState.value.loadedPlaylistId == likedPlaylistId) {
                _playlistState.value = nextState
            }
        }
    }

    private fun Playlist.isLikedPlaylistName(): Boolean {
        return name.contains("喜欢") || name.contains("收藏")
    }

    fun search(keywords: String) {
        val trimmed = keywords.trim()
        if (trimmed.isBlank()) {
            clearSearch()
            return
        }
        if (trimmed == _searchState.value.query && _searchState.value.results.isNotEmpty()) return

        val generation = ++searchGeneration
        viewModelScope.launch {
            _searchState.value = SearchUiState(query = trimmed, isSearching = true)
            repo.search(trimmed).onSuccess { resp ->
                if (generation == searchGeneration && _searchState.value.query == trimmed) {
                    _searchState.value = SearchUiState(query = trimmed, results = resp.songs, isSearching = false)
                }
            }.onFailure {
                if (generation == searchGeneration && _searchState.value.query == trimmed) {
                    _searchState.value = _searchState.value.copy(isSearching = false)
                }
            }
        }
    }

    fun clearSearch() {
        searchGeneration++
        _searchState.value = SearchUiState()
    }

    fun loadMyData(force: Boolean = false) {
        if (!force && !_myState.value.isLoading && (_myState.value.profile != null || _myState.value.playlists.isNotEmpty())) return

        viewModelScope.launch {
            _myState.value = _myState.value.copy(isLoading = true)
            val cachedProfile = session.profile
            val profile = if (cachedProfile == null || cachedProfile.userId <= 0 || cachedProfile.nickname.isBlank()) {
                repo.refreshSession().getOrNull()?.takeIf { it.loggedIn }
                session.profile
            } else {
                cachedProfile
            }
            if (profile != null) {
                repo.getUserPlaylists().onSuccess { playlists ->
                    val stats = repo.getUserStats().getOrDefault(UserStats())
                    _myState.value = myStateFrom(profile, playlists, stats)
                }.onFailure {
                    val stats = repo.getUserStats().getOrDefault(UserStats())
                    _myState.value = myStateFrom(profile, _myState.value.playlists, stats)
                }
            } else {
                repo.refreshSession().onSuccess { status ->
                    val p = if (status.loggedIn) session.profile else null
                    if (p != null) {
                        repo.getUserPlaylists().onSuccess { playlists ->
                            val stats = repo.getUserStats().getOrDefault(UserStats())
                            _myState.value = myStateFrom(p, playlists, stats)
                        }.onFailure {
                            val stats = repo.getUserStats().getOrDefault(UserStats())
                            _myState.value = myStateFrom(p, emptyList(), stats)
                        }
                    } else {
                        _myState.value = MyUiState(isLoading = false)
                    }
                }.onFailure {
                    _myState.value = MyUiState(isLoading = false)
                }
            }
        }
    }

    private fun myStateFrom(profile: UserProfile, playlists: List<Playlist>, stats: UserStats): MyUiState {
        val liked = playlists.firstOrNull { it.isLikedPlaylistName() }?.trackCount ?: 0
        return MyUiState(
            profile = profile,
            playlists = playlists,
            isLoading = false,
            likedCount = liked,
            listenCount = stats.listenCount,
            followCount = stats.followCount
        )
    }

    fun loadQuickList(type: String, force: Boolean = false) {
        if (!force) {
            quickListCache[type]?.let {
                _quickListState.value = it
                return
            }
            cache.get<QuickListUiState>(AppCache.KEY_QUICK_PREFIX + type)?.let {
                quickListCache[type] = it
                _quickListState.value = it
                return
            }
        }
        if (_quickListState.value.isLoading && _quickListState.value.loadedType == type) return

        viewModelScope.launch {
            val title = when (type) {
                "fm" -> "私人FM"
                "podcast" -> "播客推荐"
                "rank" -> "排行榜"
                "playlist" -> "热门歌单"
                else -> "推荐"
            }
            _quickListState.value = QuickListUiState(title = title, isLoading = true, loadedType = type)
            val result = when (type) {
                "fm" -> repo.getPrivateFm()
                "podcast" -> repo.getPodcastPrograms()
                "rank" -> repo.getRankings()
                "playlist" -> repo.getHotPlaylists()
                else -> repo.getHotPlaylists()
            }
            result.onSuccess { entries ->
                val state = QuickListUiState(title = title, entries = entries, isLoading = false, loadedType = type)
                quickListCache[type] = state
                _quickListState.value = state
                if (entries.isNotEmpty()) {
                    cache.put(AppCache.KEY_QUICK_PREFIX + type, state)
                }
            }.onFailure { e ->
                _quickListState.value = QuickListUiState(title = title, isLoading = false, error = e.message, loadedType = type)
            }
        }
    }

    fun loginWithCookie(cookie: String) {
        viewModelScope.launch {
            if (!cookie.contains("MUSIC_U")) {
                _loginState.value = LoginUiState(error = "尚未检测到登录状态，请完成登录后再点“我已登录”。")
                return@launch
            }
            _loginState.value = LoginUiState(isLoggingIn = true)
            repo.loginByCookie(cookie).onSuccess { resp ->
                if (resp.loggedIn) {
                    _loginState.value = LoginUiState(qrCode = 803)
                    _appState.value = AppUiState(isLoggedIn = true)
                    loadMyData(force = true)
                } else {
                    _loginState.value = LoginUiState(error = resp.error ?: "登录失败，请重试。")
                    _appState.value = AppUiState(isLoggedIn = false)
                }
            }.onFailure { e ->
                _loginState.value = LoginUiState(error = e.message ?: "登录失败，请重试。")
                _appState.value = AppUiState(isLoggedIn = false)
            }
        }
    }

    fun refreshQrLogin() {
        qrPollingJob?.cancel()
        viewModelScope.launch {
            _loginState.value = LoginUiState(isLoggingIn = true)
            repo.getQrLoginKey().onSuccess { key ->
                repo.createQrLoginCode(key).onSuccess { qr ->
                    if (qr.img.isNullOrBlank()) {
                        _loginState.value = LoginUiState(error = qr.error ?: "二维码生成失败，请重试。")
                        return@onSuccess
                    }
                    _loginState.value = LoginUiState(qrImg = qr.img, qrKey = key, qrCode = 801)
                    startQrPolling(key)
                }.onFailure { e ->
                    _loginState.value = LoginUiState(error = e.message ?: "二维码生成失败，请重试。")
                }
            }.onFailure { e ->
                _loginState.value = LoginUiState(error = e.message ?: "二维码生成失败，请重试。")
            }
        }
    }

    private fun startQrPolling(key: String) {
        qrPollingJob?.cancel()
        qrPollingJob = viewModelScope.launch {
            while (true) {
                delay(1800)
                repo.checkQrLoginCode(key).onSuccess { resp ->
                    when (resp.code) {
                        800 -> {
                            _loginState.value = _loginState.value.copy(
                                qrCode = resp.code,
                                error = "二维码已过期，请刷新后重试。"
                            )
                            return@launch
                        }
                        801, 802 -> {
                            _loginState.value = _loginState.value.copy(
                                qrCode = resp.code,
                                error = resp.message
                            )
                        }
                        803 -> {
                            repo.refreshSession()
                            _loginState.value = _loginState.value.copy(qrCode = resp.code, error = null)
                            _appState.value = AppUiState(isLoggedIn = session.isLoggedIn)
                            if (session.isLoggedIn) {
                                loadMyData(force = true)
                            }
                            return@launch
                        }
                        else -> {
                            _loginState.value = _loginState.value.copy(
                                qrCode = resp.code,
                                error = resp.message ?: resp.error
                            )
                        }
                    }
                }.onFailure { e ->
                    _loginState.value = _loginState.value.copy(error = e.message ?: "二维码状态检查失败。")
                    return@launch
                }
            }
        }
    }

    fun checkLoginStatus() {
        viewModelScope.launch {
            repo.refreshSession()
            _appState.value = AppUiState(isLoggedIn = session.isLoggedIn)
        }
    }

    fun logout() {
        viewModelScope.launch {
            qrPollingJob?.cancel()
            _appState.value = AppUiState(isLoggedIn = false)
            _loginState.value = LoginUiState()
            _myState.value = MyUiState(isLoading = false)
            playlistCache.clear()
            quickListCache.clear()
            _discoverState.value = DiscoverUiState()
            cache.clearUserData()
            repo.logout()
            session.clear()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }
}
