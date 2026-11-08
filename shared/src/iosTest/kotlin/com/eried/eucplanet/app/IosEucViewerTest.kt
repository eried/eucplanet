package com.eried.eucplanet.app

import com.eried.eucplanet.ui.recording.EucViewerPayload
import com.eried.eucplanet.ui.recording.TripMapPage
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The iPhone hands a long trip to the viewer in pieces that rejoin to the whole file. */
class IosEucViewerTest {
    @OptIn(ExperimentalEncodingApi::class)
    @Test fun aLongTripGoesInPiecesThatRejoinToTheFile() {
        val bytes = ByteArray(700_000) { (it % 251).toByte() }
        val sent = mutableListOf<String>()
        val source = IosEucViewerHost.tripSource(TripMapPage { sent += it }, EucViewerPayload(bytes, "trip.csv"))

        assertEquals("window.__eucTrip=[];", sent.first())
        val pieces = sent.drop(1).map { it.removePrefix("window.__eucTrip.push('").removeSuffix("');") }
        assertTrue(pieces.size > 1, "split into ${pieces.size}")
        assertTrue(pieces.all { it.length <= 256 * 1024 })
        assertTrue(bytes.contentEquals(Base64.decode(pieces.joinToString(""))))
        assertTrue("deliver(b)" in source && "window.__eucTrip.join('')" in source)
    }
}
