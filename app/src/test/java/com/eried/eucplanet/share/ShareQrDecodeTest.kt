package com.eried.eucplanet.share

import com.eried.eucplanet.ui.navigator.decodeQrLuminance
import com.eried.eucplanet.ui.navigator.qrReader
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The share dialog's QR scanner, minus the camera.
 *
 * Pointing a phone at a code is the only way to test the CameraX half, and an
 * emulator's virtual scene will not hold one up. What CAN go wrong silently is
 * the ZXing wiring behind it: a reader whose format hint is dropped, a
 * luminance source given the wrong geometry, or a reader that is not reset
 * between frames. These pin that half against a real encoded code.
 */
class ShareQrDecodeTest {

    /** Render a QR the way a phone screen shows it: black modules on white,
     *  as the tightly packed 8-bit luminance plane the scanner builds from a
     *  camera frame. */
    private fun luminanceOf(text: String, side: Int = 300): Triple<ByteArray, Int, Int> {
        val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, side, side)
        val gray = ByteArray(matrix.width * matrix.height)
        var i = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                gray[i++] = if (matrix.get(x, y)) 0 else 255.toByte()
            }
        }
        return Triple(gray, matrix.width, matrix.height)
    }

    @Test fun decodesAShareLinkQr() {
        val url = "https://eucplanet.ried.no/share#AAAAAAAAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB"
        val (gray, w, h) = luminanceOf(url)
        val decoded = decodeQrLuminance(gray, w, h, qrReader())
        assertEquals(url, decoded)
        // And what came off the code is a link the app can actually join.
        assertNotNull(ShareLinks.parse(decoded!!))
    }

    /** The reader is reused frame after frame, so a miss must not poison the
     *  hit that follows it. */
    @Test fun aMissedFrameDoesNotBreakTheNextOne() {
        val reader = qrReader()
        val blank = ByteArray(200 * 200) { 255.toByte() }
        assertNull(decodeQrLuminance(blank, 200, 200, reader))
        val url = "https://eucplanet.ried.no/share#CCCCCCCCCCCCCCCCCCCCCC.DDDDDDDDDDDDDDDDDDDDDD"
        val (gray, w, h) = luminanceOf(url)
        assertEquals(url, decodeQrLuminance(gray, w, h, reader))
    }

    /** A frame smaller than its declared size would run the luminance source
     *  off the end of the array; it is refused rather than thrown. */
    @Test fun aShortFrameIsRefused() {
        assertNull(decodeQrLuminance(ByteArray(10), 100, 100, qrReader()))
        assertNull(decodeQrLuminance(ByteArray(0), 0, 0, qrReader()))
    }
}
