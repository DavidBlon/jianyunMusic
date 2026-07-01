package com.ncm.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.ncm.app.R
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class MusicPlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_REFRESH = "com.ncm.app.playback.REFRESH"

        private const val TAG = "MusicPlaybackService"
        private const val NOTIFICATION_CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_PREVIOUS = "com.ncm.app.playback.PREVIOUS"
        private const val ACTION_PLAY_PAUSE = "com.ncm.app.playback.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.ncm.app.playback.NEXT"
        private const val COVER_SIZE_PX = 256

        private val coverCache = LruCache<Long, Bitmap>(12)
        private val loadingCoverIds = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
        private val failedCoverIds = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        AppPlayer.mediaSession(this)
        startAsForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "onStartCommand flags=$flags startId=$startId result=$result")
        AppPlayer.mediaSession(this)
        handleAction(intent?.action)
        startAsForegroundService()
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return AppPlayer.mediaSession(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved isPlaying=${AppPlayer.isPlaying()}")
        if (AppPlayer.isPlaying()) {
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (AppPlayer.isPlaying()) {
            Log.i(TAG, "onDestroy while playing; keeping session and restarting service")
            AppPlayer.markPlaybackServiceStopped()
            AppPlayer.startPlaybackService(applicationContext)
        } else {
            Log.i(TAG, "onDestroy while idle; releasing session")
            AppPlayer.releaseSession()
        }
        super.onDestroy()
    }

    private fun startAsForegroundService() {
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildKeepAliveNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
        Log.i(TAG, "startForeground")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "音乐播放",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "保持后台音乐播放"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildKeepAliveNotification(): Notification {
        val song = AppPlayer.currentSong()
        val isPlaying = AppPlayer.isPlaying()
        val cover = song?.let { notificationCover(it.id, it.album?.picUrl) }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(song?.name ?: "正在播放音乐")
            .setContentText(song?.artistText ?: "网易云音乐正在后台播放")
            .setLargeIcon(cover)
            .setContentIntent(AppPlayer.openAppPendingIntent(this))
            .addAction(R.drawable.ic_prev, "上一首", serviceIntent(ACTION_PREVIOUS))
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "暂停" else "播放",
                serviceIntent(ACTION_PLAY_PAUSE)
            )
            .addAction(R.drawable.ic_next, "下一首", serviceIntent(ACTION_NEXT))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notificationCover(songId: Long, imageUrl: String?): Bitmap? {
        coverCache.get(songId)?.let { return it }
        if (songId <= 0 || imageUrl.isNullOrBlank() || songId in failedCoverIds) return null
        loadNotificationCover(songId, imageUrl)
        return null
    }

    private fun loadNotificationCover(songId: Long, imageUrl: String) {
        if (!loadingCoverIds.add(songId)) return
        thread(name = "notification-cover-$songId") {
            val bitmap = runCatching {
                val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1_500
                    readTimeout = 1_500
                    useCaches = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Referer", "https://music.163.com/")
                }
                try {
                    connection.inputStream.use { input ->
                        BitmapFactory.decodeStream(input)?.let { decoded ->
                            Bitmap.createScaledBitmap(decoded, COVER_SIZE_PX, COVER_SIZE_PX, true)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

            loadingCoverIds.remove(songId)
            if (bitmap == null) {
                failedCoverIds.add(songId)
                return@thread
            }

            coverCache.put(songId, bitmap)
            mainHandler.post {
                if (AppPlayer.currentSong()?.id == songId) {
                    startAsForegroundService()
                }
            }
        }
    }

    private fun handleAction(action: String?) {
        val player = AppPlayer.player(this)
        when (action) {
            ACTION_PREVIOUS -> player.seekToPreviousMediaItem()
            ACTION_PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
            ACTION_NEXT -> player.seekToNextMediaItem()
            ACTION_REFRESH -> AppPlayer.syncCurrentFromPlayer()
        }
        AppPlayer.syncCurrentFromPlayer()
    }

    private fun serviceIntent(action: String): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, MusicPlaybackService::class.java).setAction(action),
            flags
        )
    }
}
