package com.ncm.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.MediaSession
import com.ncm.app.MainActivity
import com.ncm.app.NeteaseApp
import com.ncm.app.data.cache.LinglanCachePolicy
import com.ncm.app.data.model.Song
import com.ncm.app.util.albumArtworkUrl
import okhttp3.OkHttpClient
import java.io.IOException

object AppPlayer {
    private const val TAG = "AppPlayer"
    private const val MEDIA_EXTRA_SOURCE = "com.ncm.app.media.SOURCE"
    private const val PLUGIN_KEY_PREFIX = "plugin:"
    private const val PLAYBACK_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.0.18.203152"

    /** Media3 据此请求和响应系统音频焦点（电话、语音、短视频等）。 */
    internal val musicAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private data class PlaybackSnapshot(
        val song: Song,
        val source: String
    )

    /** 插件轨道快照：mediaId 为 pluginId#remoteId 复合键（非 Long）。 */
    private data class PluginPlaybackSnapshot(
        val track: com.ncm.app.plugin.model.OnlineTrack,
        val shellSong: Song,
        val url: String,
        val source: String
    )

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var linglanCacheDataSourceFactory: CacheDataSource.Factory? = null
    private var playbackServiceStarted = false
    private var currentSong: Song? = null
    private var currentSource: String = "netease"
    private val mediaSnapshots = mutableMapOf<Long, PlaybackSnapshot>()
    private val pluginSnapshots = mutableMapOf<String, PluginPlaybackSnapshot>()
    private val pluginItemHeaders = mutableMapOf<String, Map<String, String>>()
    private val rhythmAudioProcessor = RhythmAudioProcessor()

    /** 当前播放会话累计器（播放层单例持有，可跨 ViewModel 重建）。 */
    val sessionAccumulator = PlaySessionAccumulator()
    private var currentPlaybackSessionIdValue: String? = null
    private var currentPlaybackSessionSongIdValue: Long = 0L

    fun player(context: Context): ExoPlayer {
        val appContext = context.applicationContext
        return exoPlayer ?: ExoPlayer.Builder(appContext)
            .setRenderersFactory(
                object : DefaultRenderersFactory(appContext) {
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean
                    ): AudioSink {
                        return DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(false)
                            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                            .setAudioProcessors(arrayOf(rhythmAudioProcessor))
                            .build()
                    }
                }
            )
            .setAudioAttributes(musicAudioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        20_000,
                        90_000,
                        2_500,
                        5_000
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(playbackDataSourceFactory())
            )
            .build()
            .also { exoPlayer = it }
    }

    fun rhythmEnergy(): Float = rhythmAudioProcessor.visualEnergy()

    private fun playbackDataSourceFactory(): DataSource.Factory {
        val httpDataSourceFactory = OkHttpDataSource.Factory(playbackHttpClient())
            .setUserAgent(PLAYBACK_USER_AGENT)
        return SelectivePlaybackDataSourceFactory(
            directFactory = httpDataSourceFactory,
            linglanFactory = linglanCacheDataSourceFactory(),
            pluginFactory = { key, headers ->
                // 插件媒体：专用无平台头的客户端，per-item 请求头作为默认请求属性
                OkHttpDataSource.Factory(pluginPlaybackHttpClient())
                    .setDefaultRequestProperties(headers)
                    .createDataSource()
            },
            pluginHeaders = { key -> pluginItemHeaders[key.removePrefix(PLUGIN_KEY_PREFIX)] }
        )
    }

    private fun pluginPlaybackHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun linglanCacheDataSourceFactory(): CacheDataSource.Factory {
        return linglanCacheDataSourceFactory ?: CacheDataSource.Factory()
            .setCache(NeteaseApp.instance.linglanAudioCache.cache)
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(playbackHttpClient())
                    .setUserAgent(PLAYBACK_USER_AGENT)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .also { linglanCacheDataSourceFactory = it }
    }

    private fun playbackHttpClient(): OkHttpClient {
        // P6T2：网易云 Referer/Cookie 已移除；平台特定请求头由各来源自行携带
        return OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .header("User-Agent", PLAYBACK_USER_AGENT)
                    .header("Accept", "*/*")
                chain.proceed(builder.build())
            }
            .build()
    }

    fun startPlaybackService(context: Context) {
        if (playbackServiceStarted) return
        val appContext = context.applicationContext
        val intent = Intent(appContext, MusicPlaybackService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
            playbackServiceStarted = true
            Log.i(TAG, "startPlaybackService success")
        } catch (_: IllegalStateException) {
            Log.w(TAG, "startPlaybackService rejected by system")
            // Some ROMs reject foreground-service starts from restricted background states.
        }
    }

    fun stopPlaybackService(context: Context) {
        if (!playbackServiceStarted) return
        val appContext = context.applicationContext
        appContext.stopService(Intent(appContext, MusicPlaybackService::class.java))
        playbackServiceStarted = false
        Log.i(TAG, "stopPlaybackService")
    }

    fun isPlaying(): Boolean = exoPlayer?.isPlaying == true

    fun hasResumablePlayback(): Boolean {
        val player = exoPlayer ?: return false
        return player.mediaItemCount > 0 && player.playbackState != androidx.media3.common.Player.STATE_IDLE
    }

    fun currentSong(): Song? = currentSong

    fun currentSource(): String = currentSource

    fun sourceFor(mediaItem: MediaItem?): String? {
        return mediaItem?.mediaMetadata?.extras?.getString(MEDIA_EXTRA_SOURCE)
            ?: mediaItem?.mediaId?.toLongOrNull()?.let { mediaSnapshots[it]?.source }
    }

    fun cacheKeyFor(mediaItem: MediaItem?): String? {
        return mediaItem?.localConfiguration?.customCacheKey
    }

    fun syncCurrentFromPlayer() {
        val player = exoPlayer ?: return
        val mediaItem = player.currentMediaItem ?: return
        val mediaId = mediaItem.mediaId
        mediaId.toLongOrNull()?.let { longId ->
            val snapshot = mediaSnapshots[longId] ?: return
            currentSong = snapshot.song
            currentSource = sourceFor(mediaItem) ?: snapshot.source
            return
        }
        // 插件轨道：mediaId 是 pluginId#remoteId 复合键
        val plugin = pluginSnapshots[mediaId] ?: return
        currentSong = plugin.shellSong
        currentSource = sourceFor(mediaItem) ?: plugin.source
    }

    fun refreshPlaybackNotification(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, MusicPlaybackService::class.java)
            .setAction(MusicPlaybackService.ACTION_REFRESH)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
        } catch (_: IllegalStateException) {
            Log.w(TAG, "refreshPlaybackNotification rejected by system")
        }
    }

    fun markPlaybackServiceStopped() {
        playbackServiceStarted = false
    }

    fun mediaSession(context: Context): MediaSession {
        val appContext = context.applicationContext
        return mediaSession ?: MediaSession.Builder(appContext, player(appContext))
            .setSessionActivity(openAppPendingIntent(appContext))
            .build()
            .also { mediaSession = it }
    }

    fun mediaItem(
        song: Song,
        url: String,
        source: String = "netease",
        cacheKey: String? = null
    ): MediaItem {
        mediaSnapshots[song.id] = PlaybackSnapshot(song, source)
        val metadata = MediaMetadata.Builder()
            .setTitle(song.name)
            .setArtist(song.artistText)
            .setAlbumTitle(song.album?.name)
            .setArtworkUri(albumArtworkUrl(song.album?.picUrl)?.let(Uri::parse))
            .setExtras(Bundle().apply { putString(MEDIA_EXTRA_SOURCE, source) })
            .build()

        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(url)
            .apply {
                cacheKey
                    ?.takeIf(LinglanCachePolicy::isLinglanCacheKey)
                    ?.let(::setCustomCacheKey)
            }
            .setMediaMetadata(metadata)
            .build()
    }

    fun updateCurrentPlayback(song: Song, source: String) {
        mediaSnapshots[song.id] = PlaybackSnapshot(song, source)
        currentSong = song
        currentSource = source
    }

    /**
     * 插件轨道媒体项（spec §4）：mediaId 为复合键，请求头经「plugin:」缓存键路由到
     * 专用无网易云头的 OkHttpDataSource（Media3 1.4.1 的 RequestMetadata 不支持 per-item headers）。
     */
    fun pluginMediaItem(
        track: com.ncm.app.plugin.model.OnlineTrack,
        shellSong: Song,
        url: String,
        headers: Map<String, String>,
        source: String
    ): MediaItem {
        val composite = track.key.asComposite()
        pluginSnapshots[composite] = PluginPlaybackSnapshot(track, shellSong, url, source)
        pluginItemHeaders[composite] = headers
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artists.joinToString("/") { it.name })
            .setAlbumTitle(track.album?.name)
            .setArtworkUri(track.artworkUrl?.let(Uri::parse))
            .setExtras(Bundle().apply { putString(MEDIA_EXTRA_SOURCE, source) })
            .build()
        return MediaItem.Builder()
            .setMediaId(composite)
            .setUri(url)
            .setCustomCacheKey("$PLUGIN_KEY_PREFIX$composite")
            .setMediaMetadata(metadata)
            .build()
    }

    /** 当前插件轨道（若有）；播放器侧歌词/封面能力按需降级。 */
    fun currentPluginTrack(): com.ncm.app.plugin.model.OnlineTrack? =
        pluginSnapshots[exoPlayer?.currentMediaItem?.mediaId]?.track

    /** 媒体项切换时开启新播放会话：重置累计器并生成确定性会话 id。 */
    fun beginPlaybackSession(song: Song) {
        sessionAccumulator.beginSession()
        currentPlaybackSessionSongIdValue = song.id
        currentPlaybackSessionIdValue = "${song.id}:${sessionAccumulator.sessionStartedAt}"
    }

    fun currentPlaybackSessionId(): String? = currentPlaybackSessionIdValue

    fun currentPlaybackSessionSongId(): Long = currentPlaybackSessionSongIdValue

    fun currentPlaybackSessionStartedAt(): Long = sessionAccumulator.sessionStartedAt

    fun scheduleLinglanCacheFill(url: String, cacheKey: String) {
        NeteaseApp.instance.linglanAudioCache.scheduleFullCache(
            url = url,
            cacheKey = cacheKey,
            dataSourceFactory = {
                linglanCacheDataSourceFactory().createDataSourceForDownloading()
            }
        )
    }

    fun releaseSession() {
        mediaSession?.release()
        mediaSession = null
    }

    fun release() {
        releaseSession()
        exoPlayer?.release()
        exoPlayer = null
        runCatching { NeteaseApp.instance.linglanAudioCache.cancelPendingWrites() }
        linglanCacheDataSourceFactory = null
        currentSong = null
        currentSource = "netease"
        mediaSnapshots.clear()
        pluginSnapshots.clear()
        pluginItemHeaders.clear()
        currentPlaybackSessionIdValue = null
        currentPlaybackSessionSongIdValue = 0L
        playbackServiceStarted = false
    }

    fun openAppPendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            flags
        )
    }

    /**
     * Routes MediaItems by cache key:
     * - "linglan-audio:..." → Linglan CacheDataSource
     * - "plugin:..." → 专用无平台头的 OkHttpDataSource（插件请求头）
     * - 其他（官方网易云/酷狗）→ 普通 OkHttpDataSource
     */
    private class SelectivePlaybackDataSourceFactory(
        private val directFactory: DataSource.Factory,
        private val linglanFactory: DataSource.Factory,
        private val pluginFactory: (key: String, headers: Map<String, String>) -> DataSource,
        private val pluginHeaders: (key: String) -> Map<String, String>?
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return SelectivePlaybackDataSource(
                direct = directFactory.createDataSource(),
                linglan = linglanFactory.createDataSource(),
                pluginFactory = pluginFactory,
                pluginHeaders = pluginHeaders
            )
        }
    }

    private class SelectivePlaybackDataSource(
        private val direct: DataSource,
        private val linglan: DataSource,
        private val pluginFactory: (key: String, headers: Map<String, String>) -> DataSource,
        private val pluginHeaders: (key: String) -> Map<String, String>?
    ) : DataSource {
        private var openedSource: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            direct.addTransferListener(transferListener)
            linglan.addTransferListener(transferListener)
        }

        @Throws(IOException::class)
        override fun open(dataSpec: DataSpec): Long {
            check(openedSource == null) { "DataSource is already open" }
            val key = dataSpec.key
            val selected = when {
                LinglanCachePolicy.isLinglanCacheKey(key) -> linglan
                key?.startsWith(PLUGIN_KEY_PREFIX) == true -> {
                    val headers = pluginHeaders(key)
                        ?: throw IOException("missing plugin headers for $key")
                    pluginFactory(key, headers)
                }
                else -> direct
            }
            openedSource = selected
            return selected.open(dataSpec)
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return checkNotNull(openedSource) { "DataSource is not open" }
                .read(buffer, offset, length)
        }

        override fun getUri(): Uri? = openedSource?.uri

        override fun getResponseHeaders(): Map<String, List<String>> {
            return openedSource?.responseHeaders.orEmpty()
        }

        @Throws(IOException::class)
        override fun close() {
            val source = openedSource
            openedSource = null
            source?.close()
        }
    }
}
