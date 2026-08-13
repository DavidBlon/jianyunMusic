package com.ncm.app.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPlaybackPlayerTest {

    @Test
    fun notificationAlwaysExposesPreviousAndNextQueueCommands() {
        val player = NotificationPlaybackPlayer(
            delegate = playerWith(Player.Commands.Builder().build()),
            navigation = PlaybackNavigationBridge()
        )

        assertTrue(player.availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        assertTrue(player.availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    }

    @Test
    fun notificationPreviousAndNextUseTheAppQueueHandler() {
        var previousRequests = 0
        var nextRequests = 0
        val navigation = PlaybackNavigationBridge().apply {
            register(
                owner = this,
                onPrevious = { previousRequests++ },
                onNext = { nextRequests++ }
            )
        }
        val player = NotificationPlaybackPlayer(
            delegate = playerWith(Player.Commands.Builder().build()),
            navigation = navigation
        )

        player.seekToPreviousMediaItem()
        player.seekToNextMediaItem()

        assertEquals(1, previousRequests)
        assertEquals(1, nextRequests)
    }

    private fun playerWith(commands: Player.Commands): Player {
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "getAvailableCommands" -> commands
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "NotificationPlaybackPlayerTestDelegate"
                else -> null
            }
        } as Player
    }
}
