package com.eried.eucplanet.app

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.eried.eucplanet.platform.toByteArray
import com.eried.eucplanet.ui.settings.eucstats.PickedAvatar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIBezierPath
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerEditedImage
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.darwin.NSObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The leaderboard picture on the iPhone: the photo library with its own
 * square crop (allowsEditing), scaled to the same 256 px Android's crop
 * dialog writes, as a PNG. The navigator's marker photo uses the same picker
 * and crop ([openMarker]), cut to Android's 64 px circle.
 */
internal object IosAvatarPicker {
    private const val OUTPUT_PX = 256.0
    private const val MARKER_PX = 64.0

    // UIImagePickerController holds its delegate weakly: the open one lives here.
    private var delegate: Delegate? = null

    fun open(onPicked: (PickedAvatar?) -> Unit) = pick { image ->
        // As on Android: a new pick drops the previous crop first.
        onPicked(null)
        encode(image)?.let(onPicked)
    }

    /** The marker photo: the same crop, as the 64 px circle's `data:image/png;base64,` URL. */
    @OptIn(ExperimentalEncodingApi::class)
    fun openMarker(onPicked: (dataUrl: String) -> Unit) = pick { image ->
        circle(image, MARKER_PX)?.let { onPicked("data:image/png;base64," + Base64.Default.encode(it)) }
    }

    private fun pick(onImage: (UIImage) -> Unit) {
        val top = topViewController() ?: return
        val picker = UIImagePickerController()
        picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
        picker.allowsEditing = true
        val d = Delegate(onImage) { delegate = null }
        delegate = d
        picker.delegate = d
        top.presentViewController(picker, animated = true, completion = null)
    }

    private class Delegate(
        private val onImage: (UIImage) -> Unit,
        private val onDone: () -> Unit,
    ) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
        override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
            val image = (didFinishPickingMediaWithInfo[UIImagePickerControllerEditedImage]
                ?: didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage]) as? UIImage
            picker.dismissViewControllerAnimated(true, completion = null)
            onDone()
            if (image == null) return
            onImage(image)
        }

        override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
            picker.dismissViewControllerAnimated(true, completion = null)
            onDone()
        }
    }

    /**
     * The centre square of [image] cut to a circle [px] wide on a clear
     * background, as Android's UserMarkerCropDialog renders the marker.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun circle(image: UIImage, px: Double): ByteArray? {
        val (w, h) = image.size.useContents { width to height }
        if (w <= 0.0 || h <= 0.0) return null
        val scale = px / minOf(w, h)
        val dw = w * scale
        val dh = h * scale
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(px, px), false, 1.0)
        UIBezierPath.bezierPathWithOvalInRect(CGRectMake(0.0, 0.0, px, px)).addClip()
        image.drawInRect(CGRectMake((px - dw) / 2, (px - dh) / 2, dw, dh))
        val round = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return round?.let { UIImagePNGRepresentation(it) }?.toByteArray()
    }

    /** The centre square of [image] at [OUTPUT_PX], as the preview and the base64 PNG. */
    @OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
    private fun encode(image: UIImage): PickedAvatar? {
        val (w, h) = image.size.useContents { width to height }
        if (w <= 0.0 || h <= 0.0) return null
        val scale = OUTPUT_PX / minOf(w, h)
        val dw = w * scale
        val dh = h * scale
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(OUTPUT_PX, OUTPUT_PX), true, 1.0)
        image.drawInRect(CGRectMake((OUTPUT_PX - dw) / 2, (OUTPUT_PX - dh) / 2, dw, dh))
        val square = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        val png = square?.let { UIImagePNGRepresentation(it) }?.toByteArray() ?: return null
        val bitmap = runCatching { org.jetbrains.skia.Image.makeFromEncoded(png).toComposeImageBitmap() }
            .getOrNull() ?: return null
        return PickedAvatar(bitmap, Base64.Default.encode(png))
    }
}
