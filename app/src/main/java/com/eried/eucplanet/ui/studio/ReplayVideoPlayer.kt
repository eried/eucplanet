package com.eried.eucplanet.ui.studio

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * Real-time replay-video preview backed by [ExoPlayer] rendered into a
 * [TextureView]. A TextureView (not a SurfaceView) draws inside the normal view
 * hierarchy, so the overlay elements composite on top and the pane clips exactly
 * like every other face. The player PLAYS while the replay clock is running and
 * SEEKS while the rider scrubs - both far smoother than the per-frame
 * MediaMetadataRetriever path, which now serves only offline export.
 *
 * [videoTimeMs] is the already edge-resolved position inside the clip; [playing]
 * is true only while the replay is advancing AND the cursor sits inside the clip
 * window. [speed] matches the replay speed so playback and the cursor stay in
 * step. Audio is muted: a background clip should never talk over the ride.
 */
@Composable
fun ReplayVideoPlayer(
    uri: String,
    videoTimeMs: Long,
    playing: Boolean,
    loop: Boolean = false,
    speed: Float,
    fit: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
        }
    }

    LaunchedEffect(uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }
    // LOOP repeats the clip so it keeps rolling past its own length.
    LaunchedEffect(loop) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(playing, speed) {
        player.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
        player.playWhenReady = playing
    }
    // While playing, only correct real drift so playback stays smooth; while
    // paused/scrubbing, follow the cursor exactly. ExoPlayer coalesces rapid
    // seeks, so a fast scrub stays responsive instead of piling up decodes.
    LaunchedEffect(videoTimeMs, playing) {
        if (playing) {
            var drift = abs(player.currentPosition - videoTimeMs)
            // For a looping clip the position wraps, so near the seam the raw diff
            // is ~duration while the real gap is tiny - use the circular distance
            // so it doesn't seek every loop.
            val dur = player.duration
            if (loop && dur > 0L) drift = minOf(drift, dur - drift)
            if (drift > DRIFT_TOLERANCE_MS) player.seekTo(videoTimeMs)
        } else {
            player.seekTo(videoTimeMs)
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    var videoW by remember { mutableStateOf(0) }
    var videoH by remember { mutableStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoW = size.width
                videoH = size.height
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    var viewW by remember { mutableStateOf(0) }
    var viewH by remember { mutableStateOf(0) }
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
        modifier = modifier.onSizeChanged { viewW = it.width; viewH = it.height },
        update = { tv -> applyFit(tv, videoW, videoH, viewW, viewH, fit) }
    )
}

/**
 * Maps the clip onto the [TextureView] for the given [fit]. The TextureView's
 * default draw stretches the frame to the view bounds (that IS "STRETCH"); every
 * other mode is a centre-anchored scale correction from that baseline. The pane
 * already clips, so CROP/CENTER overflow is trimmed to the pane rect.
 */
private fun applyFit(tv: TextureView, videoW: Int, videoH: Int, viewW: Int, viewH: Int, fit: String) {
    if (videoW <= 0 || videoH <= 0 || viewW <= 0 || viewH <= 0) {
        tv.setTransform(Matrix())
        return
    }
    val viewAspect = viewW.toFloat() / viewH
    val videoAspect = videoW.toFloat() / videoH
    var sx = 1f
    var sy = 1f
    when (fit) {
        "STRETCH" -> { /* identity: fill the pane, aspect distorted */ }
        "FIT" -> if (videoAspect > viewAspect) sy = viewAspect / videoAspect else sx = videoAspect / viewAspect
        "CENTER" -> { sx = videoW.toFloat() / viewW; sy = videoH.toFloat() / viewH }
        else -> if (videoAspect > viewAspect) sx = videoAspect / viewAspect else sy = viewAspect / videoAspect // CROP
    }
    val m = Matrix()
    m.setScale(sx, sy, viewW / 2f, viewH / 2f)
    tv.setTransform(m)
    tv.invalidate()
}

/** Playing drift beyond this (ms) triggers a corrective seek; below it the player
 *  is left alone so playback stays smooth. */
private const val DRIFT_TOLERANCE_MS = 300L
