package com.ncm.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.ncm.app.data.AppCache
import com.ncm.app.data.MusicSourceSettings
import com.ncm.app.data.SessionManager
import com.ncm.app.data.api.NeteaseApi
import com.ncm.app.data.cache.COVER_IMAGE_CACHE_MAX_BYTES
import com.ncm.app.data.cache.LinglanAudioCache
import com.ncm.app.data.cache.coverImageCacheDirectory
import com.ncm.app.data.repository.MusicRepository
import com.ncm.app.data.repository.MusicSourceKeyValidator
import com.ncm.app.data.weekly.WeeklyCacheCleaner
import com.ncm.app.data.weekly.WeeklyPlayLog
import com.ncm.app.data.weekly.WeeklyRecommendationStore
import com.ncm.app.data.weekly.WeeklyDatabase
import com.ncm.app.domain.weekly.GenerateWeeklyRecommendationUseCase
import com.ncm.app.plugin.PlaybackResolver
import com.ncm.app.plugin.PluginSearchService
import com.ncm.app.plugin.runtime.ControlledHttpBridge
import com.ncm.app.plugin.runtime.HttpExecutor
import com.ncm.app.plugin.runtime.HttpRequestSpec
import com.ncm.app.plugin.runtime.HttpResult
import com.ncm.app.plugin.runtime.InMemoryPluginRuntime
import com.ncm.app.plugin.runtime.PluginRuntime
import com.ncm.app.plugin.security.SsrfGuard
import com.ncm.app.ui.theme.AccentThemeSettings
import com.ncm.app.ui.theme.PlayerAppearanceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import java.time.ZoneId

/** App 级协程作用域：周推荐生成/清理等后台任务用它，避免被调用方取消连带。 */
val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class NeteaseApp : Application(), ImageLoaderFactory {

    lateinit var api: NeteaseApi
        private set

    lateinit var repository: MusicRepository
        private set

    lateinit var session: SessionManager
        private set

    lateinit var cache: AppCache
        private set
    lateinit var musicSourceSettings: MusicSourceSettings
        private set
    lateinit var musicSourceKeyValidator: MusicSourceKeyValidator
        private set
    lateinit var linglanAudioCache: LinglanAudioCache
        private set
    lateinit var accentThemeSettings: AccentThemeSettings
        private set
    lateinit var playerAppearanceSettings: PlayerAppearanceSettings
        private set
    lateinit var weeklyPlayLog: WeeklyPlayLog
        private set
    lateinit var weeklyRecommendationStore: WeeklyRecommendationStore
        private set
    lateinit var generateWeeklyRecommendationUseCase: GenerateWeeklyRecommendationUseCase
        private set
    lateinit var weeklyCacheCleaner: WeeklyCacheCleaner
        private set

    // ---- 插件宿主（阶段 4 组装；阶段 6 由 PluginRegistry + QuickJsPluginRuntime 驱动）----
    lateinit var onlineSourceSettings: com.ncm.app.data.store.MusicSourceSettings
        private set
    lateinit var pluginRuntime: PluginRuntime
        private set
    lateinit var playbackResolver: PlaybackResolver
        private set
    lateinit var pluginSearchService: PluginSearchService
        private set
    lateinit var pluginHttpBridge: ControlledHttpBridge
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = SessionManager(this)
        cache = AppCache(this)
        musicSourceSettings = MusicSourceSettings(this)
        musicSourceKeyValidator = MusicSourceKeyValidator()
        linglanAudioCache = LinglanAudioCache(this)
        accentThemeSettings = AccentThemeSettings(this)
        playerAppearanceSettings = PlayerAppearanceSettings(this)
        cache.removePrefix(AppCache.KEY_PLAYLIST_PREFIX)
        cache.removePrefix(AppCache.KEY_QUICK_PREFIX)
        cache.remove(AppCache.KEY_DISCOVER)
        cache.remove(AppCache.KEY_MY)
        initNetwork()
        initWeeklyRecommendation()
        applicationScope.launch {
            weeklyCacheCleaner.cleanupOnAppStart(session.userId)
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(coverImageCacheDirectory(this))
                    .maxSizeBytes(COVER_IMAGE_CACHE_MAX_BYTES)
                    .build()
            }
            .build()
    }

    private fun initNetwork() {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BASIC
            else
                HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://music.163.com/")
                    .apply {
                        if (session.cookie.isNotBlank()) {
                            header("Cookie", session.cookie)
                        }
                    }
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(NeteaseApi::class.java)
        initPluginHost()
    }

    /**
     * 插件宿主组装（阶段 4）：受控 HTTP 桥（SSRF 前置校验；DNS 重绑定防护在阶段 6 的
     * 自定义 DNS 层补全）+ 播放解析器 + 搜索服务。当前运行时为内存占位，
     * 真实脚本的下载/校验/装载由 PluginRegistry 驱动（阶段 6 接线到设置页选择来源）。
     */
    private fun initPluginHost() {
        onlineSourceSettings = com.ncm.app.data.store.MusicSourceSettings(this)

        val pluginHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
        val pluginHttpExecutor: HttpExecutor = { spec ->
            val request = Request.Builder()
                .url(spec.url)
                .method(spec.method, spec.body?.let { RequestBody.create(null, it) })
                .apply { spec.headers.forEach { (k, v) -> header(k, v) } }
                .build()
            pluginHttp.newCall(request).execute().use { response ->
                HttpResult(
                    status = response.code,
                    headers = response.headers.toMultimap().mapValues { it.value.firstOrNull() ?: "" },
                    data = response.body?.bytes() ?: byteArrayOf()
                )
            }
        }
        pluginHttpBridge = ControlledHttpBridge(
            ssrfGuard = SsrfGuard(),
            executor = pluginHttpExecutor
        )

        pluginRuntime = InMemoryPluginRuntime(emptyMap())
        playbackResolver = PlaybackResolver(pluginRuntime, SsrfGuard())
        pluginSearchService = PluginSearchService(pluginRuntime) { onlineSourceSettings.currentPluginId }
        repository = MusicRepository(api, session, linglanAudioCache, musicSourceSettings, pluginSearchService)
    }

    private fun initWeeklyRecommendation() {
        weeklyPlayLog = WeeklyPlayLog(WeeklyDatabase.get(this))
        val weeklyPrefs = getSharedPreferences(WeeklyRecommendationStore.PREF_NAME, Context.MODE_PRIVATE)
        weeklyRecommendationStore = WeeklyRecommendationStore(weeklyPrefs)
        weeklyCacheCleaner = WeeklyCacheCleaner(weeklyPlayLog, weeklyRecommendationStore)
        generateWeeklyRecommendationUseCase = GenerateWeeklyRecommendationUseCase(
            source = repository,
            weeklyPlayLog = weeklyPlayLog,
            store = weeklyRecommendationStore,
            currentUserId = { session.userId },
            currentSessionGeneration = { session.sessionGeneration },
            scope = applicationScope,
            zoneIdProvider = { ZoneId.systemDefault() },
            nowMs = { System.currentTimeMillis() }
        )
    }

    companion object {
        lateinit var instance: NeteaseApp
            private set
    }
}
