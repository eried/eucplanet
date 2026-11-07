package com.eried.eucplanet.app

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.eried.eucplanet.platform.toByteArray
import com.eried.eucplanet.ui.settings.eucstats.PickedAvatar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
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
 * dialog writes, as a PNG.
 */
internal object IosAvatarPicker {
    private const val OUTPUT_PX = 256.0

    // UIImagePickerController holds its delegate weakly: the open one lives here.
    private var delegate: Delegate? = null

    fun open(onPicked: (PickedAvatar?) -> Unit) {
        val top = topViewController() ?: return
        val picker = UIImagePickerController()
        picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
        picker.allowsEditing = true
        val d = Delegate(onPicked) { delegate = null }
        delegate = d
        picker.delegate = d
        top.presentViewController(picker, animated = true, completion = null)
    }

    private class Delegate(
        private val onPicked: (PickedAvatar?) -> Unit,
        private val onDone: () -> Unit,
    ) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
        override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
            val image = (didFinishPickingMediaWithInfo[UIImagePickerControllerEditedImage]
                ?: didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage]) as? UIImage
            picker.dismissViewControllerAnimated(true, completion = null)
            onDone()
            if (image == null) return
            // As on Android: a new pick drops the previous crop first.
            onPicked(null)
            encode(image)?.let(onPicked)
        }

        override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
            picker.dismissViewControllerAnimated(true, completion = null)
            onDone()
        }
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
