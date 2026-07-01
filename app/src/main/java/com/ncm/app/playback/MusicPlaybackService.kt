package com.ncm.app.playback

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class MusicPlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return AppPlayer.mediaSession(this)
    }

    override fun onDestroy() {
        AppPlayer.releaseSession()
        super.onDestroy()
    }
}
