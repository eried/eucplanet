package com.eried.eucplanet.ui.navigator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Decode a user-picked image [uri] into a Bitmap that is safe to feed into
 * [UserMarkerCropDialog].
 *
 * Real-world phone photos are routinely 12-50+ megapixels; decoding one at
 * full resolution produces a multi-hundred-MB ARGB_8888 bitmap that either
 * (a) blows the app heap with OutOfMemoryError, or (b) exceeds
 * RecordingCanvas's 100 MB per-bitmap draw limit ("trying to draw too large
 * bitmap") the moment the crop dialog tries to render it -- both crash the
 * activity. A ~1024 px long edge is plenty for our small circular avatar.
 *
 * Two-pass decode: probe the bounds (no pixels allocated), pick a
 * power-of-two `inSampleSize` that brings the long edge to ~[targetLongEdge]
 * px, decode for real, then hard-cap the long edge with createScaledBitmap --
 * `inSampleSize` is only a hint, some HEIC / Pixel decoders return a bitmap
 * larger than requested.
 *
 * Returns null if the stream can't be opened or the image can't be decoded.
 * This is the single decode path for every picked-photo -> crop flow, so the
 * downsampling can't be accidentally skipped by a new picker.
 */
fun decodeDownsampledBitmap(
    context: Context,
    uri: Uri,
    targetLongEdge: Int = 1024,
): Bitmap? {
    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, probe)
    } ?: return null

    val longEdge = maxOf(probe.outWidth, probe.outHeight).coerceAtLeast(1)
    var sample = 1
    while (longEdge / sample > targetLongEdge) sample *= 2

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: return null

    val curLong = maxOf(decoded.width, decoded.height)
    if (curLong <= targetLongEdge) return decoded

    val scale = targetLongEdge.toFloat() / curLong
    val nw = (decoded.width * scale).toInt().coerceAtLeast(1)
    val nh = (decoded.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(decoded, nw, nh, true)
    if (scaled !== decoded) decoded.recycle()
    return scaled
}
