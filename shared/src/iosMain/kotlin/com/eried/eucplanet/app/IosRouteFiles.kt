package com.eried.eucplanet.app

import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.util.QrEncoder
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.setValue
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.NSLineBreakByTruncatingTail
import platform.UIKit.NSMutableParagraphStyle
import platform.UIKit.NSParagraphStyleAttributeName
import platform.UIKit.NSStringDrawingUsesLineFragmentOrigin
import platform.UIKit.boundingRectWithSize
import platform.UIKit.drawWithRect
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIColor
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIFont
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIRectFill
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeXML
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The route builder's GPX files on the iPhone, through the Files picker.
 *
 * Opening asks for a copy of one GPX or XML file, as Android's open picker
 * lists those types. Saving is the other way round from Android: the Files
 * export picker needs the file before it opens, so the route is written to
 * tmp under the suggested name ([exportPath]) and the picker then copies it
 * to the folder the rider picks ([written]).
 */
internal object IosGpxFiles {
    private const val TAG = "GpxFiles"

    // The picker holds its delegate weakly: the open one lives here.
    private var delegate: PickerDelegate? = null

    /** Where the route is written before the export picker copies it, always ending in .gpx. */
    @OptIn(ExperimentalForeignApi::class)
    fun exportPath(suggestedName: String): String {
        val dir = NSTemporaryDirectory() + "gpx-export"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        val safe = suggestedName.replace(Regex("[/\\\\:]"), "_").ifBlank { "route" }
        val name = if (safe.endsWith(".gpx", ignoreCase = true)) safe else "$safe.gpx"
        return "$dir/$name"
    }

    /** The route is at [path]: let the rider pick where the copy goes. */
    fun written(path: String) = onMain {
        val top = topViewController() ?: return@onMain
        val picker = UIDocumentPickerViewController(
            forExportingURLs = listOf(NSURL.fileURLWithPath(path)), asCopy = true,
        )
        val d = PickerDelegate({}) { delegate = null }
        delegate = d
        picker.delegate = d
        top.presentViewController(picker, animated = true, completion = null)
    }

    /** The Files picker for one GPX file; [onPicked] gets the app's copy. */
    fun open(onPicked: (path: String) -> Unit) {
        val top = topViewController() ?: return
        val types = listOfNotNull(UTType.typeWithFilenameExtension("gpx"), UTTypeXML)
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
        picker.allowsMultipleSelection = false
        val d = PickerDelegate(onPicked) { delegate = null }
        delegate = d
        picker.delegate = d
        top.presentViewController(picker, animated = true, completion = null)
    }

    private class PickerDelegate(
        private val onPicked: (String) -> Unit,
        private val onDone: () -> Unit,
    ) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            onDone()
            val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
            Log.i(TAG, "picked ${url.lastPathComponent}")
            url.path?.let(onPicked)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onDone()
    }
}

/** The share sheet, from whatever is on top. */
internal object IosShareSheet {
    fun share(items: List<Any>, subject: String) = onMain {
        val top = topViewController() ?: return@onMain
        val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
        // Mail takes the subject from here; the other targets ignore it.
        sheet.setValue(subject, forKey = "subject")
        top.presentViewController(sheet, animated = true, completion = null)
    }
}

/**
 * The route QR as a picture to send, as Android's RouteQrCard draws it: the
 * code, then where the route goes and how far, on white. Fixed black on white
 * on purpose: this is an exported image, not themed UI, and a scanner needs
 * the contrast whatever theme sent it.
 */
internal object IosRouteQrCard {
    private const val WIDTH = 1080.0
    private const val MARGIN = 72.0

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    fun render(link: String, title: String, distance: String, footer: String): UIImage? {
        val matrix = runCatching { QrEncoder.encode(link, QrEncoder.Ecc.M) }.getOrNull() ?: return null
        val side = WIDTH - 2 * MARGIN
        val textWidth = WIDTH - 2 * MARGIN
        fun attrs(font: UIFont, color: UIColor): Map<Any?, Any?> = mapOf(
            NSFontAttributeName to font,
            NSForegroundColorAttributeName to color,
            NSParagraphStyleAttributeName to NSMutableParagraphStyle().apply {
                setAlignment(NSTextAlignmentCenter)
                setLineBreakMode(NSLineBreakByTruncatingTail)
            },
        )
        val titleA = attrs(UIFont.boldSystemFontOfSize(46.0), UIColor.blackColor)
        val bodyA = attrs(UIFont.systemFontOfSize(38.0), UIColor(red = 0.2, green = 0.2, blue = 0.2, alpha = 1.0))
        val footA = attrs(UIFont.systemFontOfSize(30.0), UIColor(red = 0.467, green = 0.467, blue = 0.467, alpha = 1.0))
        // Each block's height, capped at its line count as Android's StaticLayout caps it.
        fun height(text: String, a: Map<Any?, Any?>, lines: Int, lineHeight: Double): Double {
            val h = NSString.create(string = text).boundingRectWithSize(
                CGSizeMake(textWidth, 10_000.0), NSStringDrawingUsesLineFragmentOrigin, a, null,
            ).useContents { size.height }
            return minOf(h, lines * lineHeight * 1.2)
        }
        val titleH = height(title, titleA, 3, 46.0)
        val distH = height(distance, bodyA, 1, 38.0)
        val footH = height(footer, footA, 2, 30.0)
        val total = MARGIN + side + 48 + titleH + 16 + distH + 36 + footH + MARGIN

        UIGraphicsBeginImageContextWithOptions(CGSizeMake(WIDTH, total), true, 1.0)
        UIColor.whiteColor.setFill()
        UIRectFill(CGRectMake(0.0, 0.0, WIDTH, total))
        UIColor.blackColor.setFill()
        val n = matrix.size
        // Whole pixels per module, the rest split as padding, as ZXing renders it.
        val multiple = maxOf(1, (side.toInt() / n))
        val pad = (side - n * multiple) / 2
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix[x, y]) {
                UIRectFill(CGRectMake(MARGIN + pad + x * multiple, MARGIN + pad + y * multiple, multiple.toDouble(), multiple.toDouble()))
            }
        }
        var top = MARGIN + side + 48
        for ((text, a, h, gap) in listOf(
            Block(title, titleA, titleH, 16.0), Block(distance, bodyA, distH, 36.0), Block(footer, footA, footH, 0.0),
        )) {
            NSString.create(string = text).drawWithRect(
                CGRectMake(MARGIN, top, textWidth, h), NSStringDrawingUsesLineFragmentOrigin, a, null,
            )
            top += h + gap
        }
        val image = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return image
    }

    private data class Block(val text: String, val attrs: Map<Any?, Any?>, val height: Double, val gap: Double)
}

/** Runs [block] on the main thread, now when already there. */
internal fun onMain(block: () -> Unit) {
    if (platform.Foundation.NSThread.isMainThread) block()
    else dispatch_async(dispatch_get_main_queue()) { block() }
}
