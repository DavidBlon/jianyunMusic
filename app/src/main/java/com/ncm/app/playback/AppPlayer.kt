package com.ncm.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.MediaSession
import com.ncm.app.MainActivity
import com.ncm.app.NeteaseApp
import com.ncm.app.data.model.Song
import com.ncm.app.util.sizedImageUrl
import okhttp3.OkHttpClient
import java.io.File

object AppPlayer {
    private const val TAG = "AppPlayer"
    private const val MEDIA_EXTRA_SOURCE = "com.ncm.app.media.SOURCE"
    private const val PLAYBACK_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.0.18.203152"

    private data class PlaybackSnapshot(
        val song: Song,
        val source: String
    )

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var mediaCache: SimpleCache? = null
    private var playbackServiceStarted = false
    private var currentSong: Song? = null
    private var currentSource: String = "netease"
    private val mediaSnapshots = mutableMapOf<Long, PlaybackSnapshot>()

    fun player(context: Context): ExoPlayer {
        val appContext = context.applicationContext
        return exoPlayer ?: ExoPlayer.Builder(appContext)
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
                DefaultMediaSourceFactory(cacheDataSourceFactory(appContext))
            )
            .build()
            .also { exoPlayer = it }
    }

    private fun cacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        val httpDataSourceFactory = OkHttpDataSource.Factory(playbackHttpClient())
            .setUserAgent(PLAYBACK_USER_AGENT)

        return CacheDataSource.Factory()
            .setCache(mediaCache(context))
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun playbackHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .header("User-Agent", PLAYBACK_USER_AGENT)
                    .header("Referer", "https://music.163.com/")
                    .header("Origin", "https://music.163.com")
                    .header("Accept", "*/*")
                val cookie = runCatching { NeteaseApp.instance.session.cookie }.getOrDefault("")
                if (cookie.isNotBlank()) {
                    builder.header("Cookie", cookie)
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    private fun mediaCache(context: Context): SimpleCache {
        return mediaCache ?: SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(512L * 1024L * 1024L),
            StandaloneDatabaseProvider(context)
        ).also { mediaCache = it }
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

    fun syncCurrentFromPlayer() {
        val player = exoPlayer ?: return
        val mediaItem = player.currentMediaItem ?: return
        val mediaId = mediaItem.mediaId.toLongOrNull() ?: return
        val snapshot = mediaSnapshots[mediaId] ?: return
        currentSong = snapshot.song
        currentSource = sourceFor(mediaItem) ?: snapshot.source
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

    fun mediaItem(song: Song, url: String, source: String = "netease"): MediaItem {
        mediaSnapshots[song.id] = PlaybackSnapshot(song, source)
        val metadata = MediaMetadata.Builder()
            .setTitle(song.name)
            .setArtist(song.artistText)
            .setAlbumTitle(song.album?.name)
            .setArtworkUri(sizedImageUrl(song.album?.picUrl, 300)?.let(Uri::parse))
            .setExtras(Bundle().apply { putString(MEDIA_EXTRA_SOURCE, source) })
            .build()

        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
    }

    fun updateCurrentPlayback(song: Song, source: String) {
        mediaSnapshots[song.id] = PlaybackSnapshot(song, source)
        currentSong = song
        currentSource = source
    }

    fun releaseSession() {
        mediaSession?.release()
        mediaSession = null
    }

    fun release() {
        releaseSession()
        exoPlayer?.release()
        exoPlayer = null
        mediaCache?.release()
        mediaCache = null
        currentSong = null
        currentSource = "netease"
        mediaSnapshots.clear()
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
}
