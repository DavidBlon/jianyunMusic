package com.ncm.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.ncm.app.data.AppCache
import com.ncm.app.data.MusicSourceSettings
import com.ncm.app.data.SessionManager
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
import com.ncm.app.plugin.credential.KeystoreSecretVault
import com.ncm.app.plugin.credential.LinglanCredentialStore
import com.ncm.app.plugin.registry.PluginRegistry
import com.ncm.app.plugin.registry.RegistryPluginRuntime
import com.ncm.app.plugin.runtime.ControlledHttpBridge
import com.ncm.app.plugin.runtime.HttpExecutor
import com.ncm.app.plugin.runtime.HttpRequestSpec
import com.ncm.app.plugin.runtime.HttpResult
import com.ncm.app.plugin.runtime.PluginRuntime
import com.ncm.app.plugin.runtime.PluginScriptCache
import com.ncm.app.plugin.runtime.QuickJsPluginRuntime
import com.ncm.app.plugin.security.ManifestSignatureVerifier
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
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.time.ZoneId

/** App 级协程作用域：周推荐生成/清理等后台任务用它，避免被调用方取消连带。 */
val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** 中性应用入口别名（P6T4）：旧类名保留到功能稳定后的包名/工程名迁移（spec §13）。 */
typealias JianyunApp = NeteaseApp

class NeteaseApp : Application(), ImageLoaderFactory {

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
    lateinit var pluginRegistry: com.ncm.app.plugin.registry.PluginRegistry
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
        initPluginHost()
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

    /**
     * 插件宿主组装：受控 HTTP 桥（SSRF 前置校验）+ PluginRegistry（下载/签名门禁/两步装载）
     * + 播放解析器 + 搜索服务。真实脚本联调前置（§17：稳定清单 ID、签名信任根、授权状态机）
     * 未满足前，选择来源会得到明确的「签名门禁未就绪」错误，不会静默失败。
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

        // 脚本缓存键 = 用户授权身份的不可逆摘要（GC #10）：从 Keystore 密钥派生，绝不用原始密钥
        val identityDigest = runCatching {
            val secret = LinglanCredentialStore(
                KeystoreSecretVault(this, LINGLAN_AUTH_ALIAS)
            ).read().orEmpty()
            ManifestSignatureVerifier.sha256Hex(secret.ifBlank { "anonymous" })
        }.getOrDefault(ManifestSignatureVerifier.sha256Hex("anonymous"))
        val scriptCache = PluginScriptCache(
            dir = File(filesDir, "plugins").apply { mkdirs() },
            identityDigest = identityDigest
        )
        pluginRegistry = PluginRegistry(
            runtimeFactory = { pluginId, script, hostParams ->
                QuickJsPluginRuntime(pluginId, script, hostParams)
            },
            downloader = { url ->
                val request = Request.Builder().url(url).get().build()
                pluginHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("插件下载失败：HTTP ${response.code}")
                    }
                    response.body?.bytes() ?: byteArrayOf()
                }
            },
            verifier = ManifestSignatureVerifier(trustRootB64 = "", now = { System.currentTimeMillis() }),
            cache = scriptCache
        )

        pluginRuntime = RegistryPluginRuntime(pluginRegistry)
        playbackResolver = PlaybackResolver(pluginRuntime, SsrfGuard())
        pluginSearchService = PluginSearchService(pluginRuntime) { onlineSourceSettings.currentPluginId }
        repository = MusicRepository(pluginSearchService)
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

        /** 聆澜授权密钥的 Keystore 别名（与设置页一致；对应文件已排除备份）。 */
        private const val LINGLAN_AUTH_ALIAS = "linglan_auth"
    }
}
