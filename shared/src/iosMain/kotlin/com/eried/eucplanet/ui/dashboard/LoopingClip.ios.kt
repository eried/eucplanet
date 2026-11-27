package com.eried.eucplanet.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.eried.eucplanet.resources.Res
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.UIKit.UIView

/**
 * The clip from the iOS resources (generateIosAppArt copies the phone app's
 * asset), looped by AVPlayerLooper as Android's player repeats it. Muted, and
 * the file has no sound track, so it never takes the audio from music or the
 * voice.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalResourceApi::class)
@Composable
internal actual fun LoopingClip(assetName: String, modifier: Modifier) {
    val uri by produceState<String?>(null, assetName) {
        value = runCatching { Res.getUri("files/$assetName") }.getOrNull()
    }
    val url = uri?.let { NSURL.URLWithString(it) }
    if (url == null) {
        Box(modifier)
        return
    }
    val clip = remember(url) { Clip(url) }
    DisposableEffect(clip) { onDispose { clip.stop() } }
    UIKitView(factory = { clip.view }, modifier = modifier)
}

@OptIn(ExperimentalForeignApi::class)
private class Clip(url: NSURL) {
    val player = AVQueuePlayer()
    // Held for as long as the clip plays: the looper stops when it is freed.
    private val looper = AVPlayerLooper.playerLooperWithPlayer(player, AVPlayerItem.playerItemWithURL(url))
    val view = ClipView(AVPlayerLayer.playerLayerWithPlayer(player))

    init {
        player.muted = true
        player.play()
    }

    fun stop() {
        looper.disableLooping()
        player.pause()
    }
}

/** A view that keeps its player layer the size of itself. */
@OptIn(ExperimentalForeignApi::class)
private class ClipView(private val playerLayer: AVPlayerLayer) : UIView(frame = CGRectZero.readValue()) {
    init {
        clipsToBounds = true
        playerLayer.videoGravity = AVLayerVideoGravityResizeAspect
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        playerLayer.frame = bounds
    }
}
