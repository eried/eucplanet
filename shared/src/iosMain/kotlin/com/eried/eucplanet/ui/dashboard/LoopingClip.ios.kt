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
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import kotlinx.cinterop.usePinned
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.CoreFoundation.CFDataRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.ImageIO.CGAnimateImageDataWithBlock
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode

/**
 * The clip from the iOS resources (generateIosAppArt copies the phone app's
 * asset), played by ImageIO frame by frame at the file's own timing, looping
 * as the file says, as Android's AnimatedImageDrawable does.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalResourceApi::class, BetaInteropApi::class)
@Composable
internal actual fun LoopingClip(assetName: String, modifier: Modifier) {
    val bytes by produceState<ByteArray?>(null, assetName) {
        value = runCatching { Res.readBytes("files/$assetName") }.getOrNull()
    }
    val clip = bytes
    if (clip == null || clip.isEmpty()) {
        Box(modifier)
        return
    }
    // Set when the step leaves, so ImageIO stops decoding frames for a gone view.
    val stopped = remember(clip) { booleanArrayOf(false) }
    DisposableEffect(clip) { onDispose { stopped[0] = true } }
    UIKitView(
        factory = {
            val view = UIImageView()
            view.contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
            view.clipsToBounds = true
            val data = clip.usePinned { NSData.create(bytes = it.addressOf(0), length = clip.size.toULong()) }
            val cf = CFBridgingRetain(data) as CFDataRef?
            CGAnimateImageDataWithBlock(cf, null) { _, image, stop ->
                if (stopped[0]) {
                    stop?.pointed?.value = true
                } else if (image != null) {
                    view.image = UIImage.imageWithCGImage(image)
                }
            }
            CFBridgingRelease(cf)
            view
        },
        modifier = modifier,
    )
}
