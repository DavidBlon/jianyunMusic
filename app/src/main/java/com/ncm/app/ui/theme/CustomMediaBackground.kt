package com.ncm.app.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

@Composable
fun CustomMediaBackground(
    media: PlayerCustomBackground,
    initialVideoPositionMs: Long,
    onVideoPositionSaved: (String, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    when (media.type) {
        PlayerCustomMediaType.IMAGE -> AsyncImage(
            model = media.uri,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        PlayerCustomMediaType.VIDEO -> MutedLoopingVideo(
            uri = media.uri,
            initialPositionMs = initialVideoPositionMs,
            onPositionSaved = { positionMs -> onVideoPositionSaved(media.uri, positionMs) },
            modifier = modifier
        )
    }
}

@Composable
fun CustomBackgroundScrim(
    global: Boolean = false,
    modifier: Modifier = Modifier
) {
    val stops = if (global) {
        arrayOf(
            0f to Color.Black.copy(alpha = 0.46f),
            0.44f to Color.Black.copy(alpha = 0.30f),
            1f to Color.Black.copy(alpha = 0.58f)
        )
    } else {
        arrayOf(
            0f to Color.Black.copy(alpha = 0.54f),
            0.42f to Color.Black.copy(alpha = 0.18f),
            1f to Color.Black.copy(alpha = 0.68f)
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colorStops = stops))
    )
}

@Composable
private fun MutedLoopingVideo(
    uri: String,
    initialPositionMs: Long,
    onPositionSaved: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentPositionSaver by rememberUpdatedState(onPositionSaved)
    var firstFrameRendered by remember(uri) { mutableStateOf(false) }
    val firstFrameAlpha by animateFloatAsState(
        targetValue = if (firstFrameRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "videoFirstFrameAlpha"
    )
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setMaxVideoSize(1280, 720)
                .setMaxVideoFrameRate(30)
                .setMaxVideoBitrate(2_500_000)
                .build()
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    firstFrameRendered = true
                }
            })
            setMediaItem(MediaItem.fromUri(uri))
            if (initialPositionMs > 0L) seekTo(initialPositionMs)
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.play()
                Lifecycle.Event.ON_STOP -> {
                    currentPositionSaver(player.currentPosition)
                    player.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentPositionSaver(player.currentPosition)
            player.release()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )
        if (firstFrameAlpha < 1f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 1f - firstFrameAlpha))
            )
        }
    }
}
