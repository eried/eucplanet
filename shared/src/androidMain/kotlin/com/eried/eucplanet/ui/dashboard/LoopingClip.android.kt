package com.eried.eucplanet.ui.dashboard

import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * A small looping, soundless clip played from a video asset.
 *
 * It was an animated image of 4.4 MB, in every install for a page shown once.
 * The same clip as H.264 is a tenth of that. The player is the one the Overlay
 * Studio already brings, drawing into a TextureView so the rounded corners of
 * the caller's clip apply. It is muted and the file has no sound track, so it
 * never takes the audio from music or the voice.
 */
@Composable
internal actual fun LoopingClip(assetName: String, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(assetName) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri("asset:///$assetName"))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
    )
}
