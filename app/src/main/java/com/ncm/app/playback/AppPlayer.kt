package com.ncm.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.ncm.app.MainActivity
import com.ncm.app.data.model.Song

object AppPlayer {
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    fun player(context: Context): ExoPlayer {
        val appContext = context.applicationContext
        return exoPlayer ?: ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setUserAgent("Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                        .setDefaultRequestProperties(
                            mapOf(
                                "Referer" to "https://music.163.com/",
                                "Origin" to "https://music.163.com"
                            )
                        )
                )
            )
            .build()
            .also { exoPlayer = it }
    }

    fun mediaSession(context: Context): MediaSession {
        val appContext = context.applicationContext
        return mediaSession ?: MediaSession.Builder(appContext, player(appContext))
            .setSessionActivity(openAppPendingIntent(appContext))
            .build()
            .also { mediaSession = it }
    }

    fun mediaItem(song: Song, url: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.name)
            .setArtist(song.artistText)
            .setAlbumTitle(song.album?.name)
            .setArtworkUri(song.album?.picUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .build()

        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
    }

    fun releaseSession() {
        mediaSession?.release()
        mediaSession = null
    }

    fun release() {
        releaseSession()
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
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
