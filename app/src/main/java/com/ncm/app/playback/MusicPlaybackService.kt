package com.ncm.app.playback

import android.content.Intent
import android.util.Log
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ncm.app.R

class MusicPlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_REFRESH = "com.ncm.app.playback.REFRESH"

        private const val TAG = "MusicPlaybackService"
        private const val NOTIFICATION_CHANNEL_ID = "music_playback_media"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        configureMediaNotification()

        // Register the app's session with Media3 so it can own the foreground
        // notification and expose a real system media-session notification.
        addSession(AppPlayer.mediaSession(this))
        getSystemService(android.app.NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        Log.i(TAG, "onCreate: Media3 media notification configured")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "onStartCommand flags=$flags startId=$startId result=$result")
        handleAction(intent?.action)
        AppPlayer.syncCurrentFromPlayer()
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

    private fun configureMediaNotification() {
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setChannelName(R.string.notification_channel_name)
            .build()
        provider.setSmallIcon(R.drawable.ic_music_note)
        setMediaNotificationProvider(provider)
    }

    private fun handleAction(action: String?) {
        if (action == ACTION_REFRESH) {
            AppPlayer.syncCurrentFromPlayer()
        }
    }
}
