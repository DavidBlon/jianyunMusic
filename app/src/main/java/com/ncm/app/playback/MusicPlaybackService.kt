package com.ncm.app.playback

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.ncm.app.R

class MusicPlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_REFRESH = "com.ncm.app.playback.REFRESH"

        private const val TAG = "MusicPlaybackService"
        private const val NOTIFICATION_CHANNEL_ID = "music_playback_media"
        private const val NOTIFICATION_ID = 1001
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        configureMediaNotification()

        // Register the app's session with Media3 so it owns the foreground
        // notification and exposes a real system media session.
        addSession(AppPlayer.mediaSession(this))
        getSystemService(android.app.NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        Log.i(TAG, "onCreate: persistent Media3 notification configured")
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

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean
    ) {
        val player = session.player
        val hasResumablePlayback =
            player.mediaItemCount > 0 && player.playbackState != Player.STATE_IDLE

        // Media3 normally detaches the service from the foreground as soon as playback is paused.
        // Keeping a prepared item in the foreground prevents long pauses from losing the service,
        // notification and media controls. Explicit stop still clears the queue and allows teardown.
        super.onUpdateNotification(
            session,
            startInForegroundRequired || hasResumablePlayback
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (AppPlayer.hasResumablePlayback()) {
            Log.i(TAG, "onTaskRemoved: keeping resumable playback session alive")
            return
        }
        Log.i(TAG, "onTaskRemoved: no resumable playback; stopping normally")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val shouldRestart = AppPlayer.hasResumablePlayback()
        AppPlayer.markPlaybackServiceStopped()
        if (shouldRestart) {
            Log.i(TAG, "onDestroy with resumable playback; restarting service")
            AppPlayer.startPlaybackService(applicationContext)
        } else {
            Log.i(TAG, "onDestroy without resumable playback; releasing session")
            AppPlayer.releaseSession()
        }
        super.onDestroy()
    }

    private fun configureMediaNotification() {
        val defaultProvider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setChannelName(R.string.notification_channel_name)
            .build()
            .also { it.setSmallIcon(R.drawable.ic_music_note) }

        setMediaNotificationProvider(
            object : MediaNotification.Provider {
                override fun createNotification(
                    mediaSession: MediaSession,
                    customLayout: ImmutableList<CommandButton>,
                    actionFactory: MediaNotification.ActionFactory,
                    onNotificationChangedCallback: MediaNotification.Provider.Callback
                ): MediaNotification {
                    val notification = defaultProvider.createNotification(
                        mediaSession,
                        customLayout,
                        actionFactory
                    ) {
                        // DefaultMediaNotificationProvider may finish loading artwork asynchronously.
                        // Rebuild through onUpdateNotification instead of forwarding its callback:
                        // Media3's callback path otherwise recalculates a paused player as background
                        // and immediately detaches this service from the foreground again.
                        mainHandler.post {
                            if (sessions.contains(mediaSession)) {
                                onUpdateNotification(
                                    mediaSession,
                                    startInForegroundRequired = true
                                )
                            }
                        }
                    }
                    return notification.asPersistentPlaybackNotification()
                }

                override fun handleCustomCommand(
                    session: MediaSession,
                    action: String,
                    extras: Bundle
                ): Boolean = defaultProvider.handleCustomCommand(session, action, extras)
            }
        )
    }

    private fun MediaNotification.asPersistentPlaybackNotification(): MediaNotification {
        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR
        notification.flags = notification.flags and Notification.FLAG_AUTO_CANCEL.inv()
        notification.deleteIntent = null
        return MediaNotification(notificationId, notification)
    }

    private fun handleAction(action: String?) {
        if (action == ACTION_REFRESH) {
            AppPlayer.syncCurrentFromPlayer()
        }
    }
}
