package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The route QR, end to end through the same library the app's camera uses:
 * the code the Share route window draws (error correction M) decodes back to
 * the link, and the link back to the route.
 */
class RouteQrScanTest {

    private fun roundTrip(link: String): String {
        val side = 600
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, side, side,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
        val px = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, px)))
        return MultiFormatReader().decode(bitmap,
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)).text
    }

    @Test fun `a full 25-stop route scans back to the same route`() {
        val stops = (0 until 25).map { Waypoint(69.6489 + it * 0.0123, 18.9551 - it * 0.0071, name = "Stop ${it + 1}") }
        val link = RouteLinks.format(SharedRoute("Long day out", TravelMode.CYCLING, stops))!!
        val scanned = roundTrip(link)
        assertEquals(link, scanned)
        assertEquals(25, RouteLinks.parse(scanned)!!.stops.size)
    }

    @Test fun `a simplified track at the size limit scans back`() {
        val track = (0 until 8000).map { i -> GeoPoint(69.6 + i * 0.00005, 18.9 + Math.sin(i / 40.0) * 0.002) }
        val link = RouteLinks.format(SharedRoute(null, TravelMode.STRAIGHT, emptyList(), track))!!
        assertEquals(link, roundTrip(link))
    }
}
