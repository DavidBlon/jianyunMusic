package com.ncm.app.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Routes system notification navigation to the app's resolved playback queue. */
internal class PlaybackNavigationBridge {
    private data class Handler(
        val owner: Any,
        val onPrevious: () -> Unit,
        val onNext: () -> Unit
    )

    @Volatile
    private var handler: Handler? = null

    fun register(owner: Any, onPrevious: () -> Unit, onNext: () -> Unit) {
        handler = Handler(owner, onPrevious, onNext)
    }

    fun unregister(owner: Any) {
        if (handler?.owner === owner) handler = null
    }

    fun requestPrevious(): Boolean {
        val current = handler ?: return false
        current.onPrevious()
        return true
    }

    fun requestNext(): Boolean {
        val current = handler ?: return false
        current.onNext()
        return true
    }
}

/**
 * MediaSession-facing player that keeps both queue navigation commands available and forwards
 * them to [PlaybackNavigationBridge]. The delegate remains responsible for all other playback.
 */
@UnstableApi
internal class NotificationPlaybackPlayer(
    delegate: Player,
    private val navigation: PlaybackNavigationBridge
) : ForwardingPlayer(delegate) {

    override fun getAvailableCommands(): Player.Commands {
        return super.getAvailableCommands()
            .buildUpon()
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .build()
    }

    override fun seekToPreviousMediaItem() {
        if (!navigation.requestPrevious()) super.seekToPreviousMediaItem()
    }

    override fun seekToPrevious() {
        if (!navigation.requestPrevious()) super.seekToPrevious()
    }

    override fun seekToNextMediaItem() {
        if (!navigation.requestNext()) super.seekToNextMediaItem()
    }

    override fun seekToNext() {
        if (!navigation.requestNext()) super.seekToNext()
    }
}
