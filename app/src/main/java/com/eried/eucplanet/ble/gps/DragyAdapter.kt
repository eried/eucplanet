package com.eried.eucplanet.ble.gps

import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.ExternalGpsSource
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dragy / Dragy Lite GPS performance-meter adapter.
 *
 * BLE: Nordic UART (same service triple as RaceBox / InMotion V14), so the
 * shared [ExternalGpsConnectionManager] drives it unchanged; matching is
 * name-based.
 *
 * Protocol: Dragy is a closed product with no public spec, but it is built on
 * a u-blox GNSS module (the DRG70 uses a u-blox 10th-gen chip) and the
 * community "OpenDragy" DIY clone streams raw u-blox UBX messages over Nordic
 * UART: NAV-POSLLH (position) + NAV-VELNED (speed/heading) + NAV-SAT
 * (satellites) at 10 Hz, and modern u-blox setups also emit the all-in-one
 * NAV-PVT. So this adapter parses the standard UBX frame envelope (identical
 * to RaceBox) and decodes whichever of those messages arrives:
 *
 *   NAV-PVT     (0x01/0x07)  full position + ground speed + heading + sats
 *   NAV-POSLLH  (0x01/0x02)  lon / lat / height / accuracy
 *   NAV-VELNED  (0x01/0x12)  ground speed + heading + vertical velocity
 *   NAV-SAT     (0x01/0x35)  satellite count
 *
 * POSLLH/VELNED/SAT carry no fix-status field, so the merge path gates on a
 * NAV-SAT satellite count (>= MIN_FIX_SATS) as a fix proxy; NAV-PVT gates on
 * its own fixType. Every UBX message type we DON'T decode, and any non-UBX
 * (NMEA-looking) data, is logged once to diagnostics so a tester capture
 * confirms exactly what the real Dragy streams. This is XIMA-style capture
 * insurance: the decode above is our best read of the format from the u-blox
 * + OpenDragy evidence, to be confirmed against a real Dragy Lite.
 *
 * UNCONFIRMED until a tester capture: (1) the device's exact advertised name
 * ([matches] is a best guess), (2) that it really speaks Nordic UART + UBX
 * rather than NMEA or a wrapped format, (3) the exact message set.
 */
@Singleton
class DragyAdapter @Inject constructor() : ExternalGpsAdapter {

    override val source: ExternalGpsSource = ExternalGpsSource.DRAGY

    /**
     * Best-guess advertised-name match pending a real Dragy Lite scan. Covers
     * "Dragy", "DRAGY", "Dragy Lite", the DIY "DIYDragy", and the DRG-series
     * model codes (DRG70 / DRG69 / DRG80). Kept Dragy-specific so it can't
     * claim another vendor's box.
     */
    override fun matches(deviceName: String): Boolean {
        val n = deviceName.trim()
        return n.startsWith("Dragy", ignoreCase = true) ||
            n.startsWith("DRG", ignoreCase = true)
    }

    /** Rolling buffer of raw notification bytes; UBX frames straddle chunks. */
    private val buffer = ArrayDeque<Byte>()

    // Running merged fix, filled across the POSLLH / VELNED / SAT triple.
    @Volatile private var haveLat = false
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastAltM = 0f
    private var lastAccM = 0f
    private var lastSpeedKmh = 0f
    private var lastHeadingDeg: Float? = null
    private var lastVertMps: Float? = null
    private var lastSats: Int? = null

    /** UBX (cls,id) combos already logged, so the capture note fires once each. */
    private val seenTypes = HashSet<Int>()
    @Volatile private var loggedNonUbx = false

    override fun decode(notification: ByteArray): ExternalGpsSample? {
        if (notification.isEmpty()) return null
        notification.forEach { buffer.addLast(it) }

        var emitted: ExternalGpsSample? = null
        // Drain every complete frame currently in the buffer; keep the last
        // sample produced (samples arrive faster than the UI needs).
        while (true) {
            // Re-sync to a UBX header, noting any leading non-UBX bytes once
            // (real Dragy might stream NMEA text, which starts with '$').
            if (buffer.size >= 1 && buffer.first() != UBX_SYNC1) {
                if (!loggedNonUbx && buffer.first() == '$'.code.toByte()) {
                    loggedNonUbx = true
                    DiagnosticsLogger.note("dragy: non-UBX data (looks like NMEA); capture needed")
                }
            }
            while (buffer.size >= 2 &&
                !(buffer[0] == UBX_SYNC1 && buffer[1] == UBX_SYNC2)) {
                buffer.removeFirst()
            }
            if (buffer.size < 8) break // need sync+cls+id+len

            val cls = buffer[2].toInt() and 0xFF
            val id = buffer[3].toInt() and 0xFF
            val len = (buffer[4].toInt() and 0xFF) or ((buffer[5].toInt() and 0xFF) shl 8)
            val total = 6 + len + 2
            if (total > MAX_FRAME) { buffer.removeFirst(); continue } // garbage length, resync
            if (buffer.size < total) break // wait for more chunks

            val frame = ByteArray(total) { buffer.removeFirst() }
            if (!checksumValid(frame)) continue

            val sample = handleUbx(cls, id, len, frame)
            if (sample != null) emitted = sample
        }
        return emitted
    }

    private fun handleUbx(cls: Int, id: Int, len: Int, frame: ByteArray): ExternalGpsSample? {
        val p = 6 // payload start
        return when {
            cls == 0x01 && id == 0x07 && len >= 92 -> parseNavPvt(frame, p)
            cls == 0x01 && id == 0x02 && len >= 28 -> { updatePosLlh(frame, p); mergedSample() }
            cls == 0x01 && id == 0x12 && len >= 36 -> { updateVelNed(frame, p); mergedSample() }
            cls == 0x01 && id == 0x35 && len >= 8 -> {
                lastSats = frame[p + 5].toInt() and 0xFF
                null // SAT alone isn't a fix update
            }
            else -> {
                val key = (cls shl 8) or id
                if (seenTypes.add(key)) {
                    DiagnosticsLogger.note(
                        "dragy: unhandled UBX cls=0x%02x id=0x%02x len=%d".format(cls, id, len)
                    )
                }
                null
            }
        }
    }

    /** All-in-one NAV-PVT (same layout as RaceBox's standard PVT). */
    private fun parseNavPvt(frame: ByteArray, p: Int): ExternalGpsSample? {
        val fixType = frame[p + 20].toInt() and 0xFF
        if (fixType != 2 && fixType != 3 && fixType != 4) return null
        val numSV = frame[p + 23].toInt() and 0xFF
        val lon = readI32(frame, p + 24) * 1e-7
        val lat = readI32(frame, p + 28) * 1e-7
        val hMsl = readI32(frame, p + 36) / 1000f
        val hAcc = readU32(frame, p + 40) / 1000f
        val velD = readI32(frame, p + 56)
        val gSpeed = readI32(frame, p + 60).coerceAtLeast(0)
        val head = readI32(frame, p + 64) * 1e-5f
        return ExternalGpsSample(
            source = ExternalGpsSource.DRAGY,
            speedKmh = gSpeed * 0.0036f,        // mm/s -> km/h
            latitude = lat,
            longitude = lon,
            altitudeMeters = hMsl,
            accuracyMeters = hAcc,
            headingDeg = wrap360(head),
            verticalSpeedMps = -velD / 1000f,   // mm/s down -> m/s up
            numSatellites = numSV
        )
    }

    /** NAV-POSLLH: iTOW@0, lon@4, lat@8, height@12, hMSL@16, hAcc@20, vAcc@24. */
    private fun updatePosLlh(frame: ByteArray, p: Int) {
        lastLon = readI32(frame, p + 4) * 1e-7
        lastLat = readI32(frame, p + 8) * 1e-7
        lastAltM = readI32(frame, p + 16) / 1000f  // hMSL, mm -> m
        lastAccM = readU32(frame, p + 20) / 1000f  // hAcc, mm -> m
        haveLat = true
    }

    /** NAV-VELNED: velD@12 (cm/s), gSpeed@20 (cm/s 2D), heading@24 (deg 1e-5). */
    private fun updateVelNed(frame: ByteArray, p: Int) {
        val gSpeedCmS = readU32(frame, p + 20)             // cm/s ground speed
        lastSpeedKmh = (gSpeedCmS * 0.036f)                // cm/s -> km/h
        lastHeadingDeg = wrap360(readI32(frame, p + 24) * 1e-5f)
        lastVertMps = -readI32(frame, p + 12) / 100f       // cm/s down -> m/s up
    }

    /** Emit the merged POSLLH+VELNED+SAT fix once we have a position and a
     *  plausible satellite count (POSLLH/VELNED carry no fix flag). */
    private fun mergedSample(): ExternalGpsSample? {
        if (!haveLat) return null
        val sats = lastSats
        if (sats != null && sats < MIN_FIX_SATS) return null
        return ExternalGpsSample(
            source = ExternalGpsSource.DRAGY,
            speedKmh = lastSpeedKmh,
            latitude = lastLat,
            longitude = lastLon,
            altitudeMeters = lastAltM,
            accuracyMeters = lastAccM,
            headingDeg = lastHeadingDeg,
            verticalSpeedMps = lastVertMps,
            numSatellites = sats
        )
    }

    // UBX Fletcher-8 checksum over class..payload (bytes 2 .. len-3).
    private fun checksumValid(frame: ByteArray): Boolean {
        var a = 0; var b = 0
        for (i in 2 until frame.size - 2) {
            a = (a + (frame[i].toInt() and 0xFF)) and 0xFF
            b = (b + a) and 0xFF
        }
        return a == (frame[frame.size - 2].toInt() and 0xFF) &&
            b == (frame[frame.size - 1].toInt() and 0xFF)
    }

    private fun readI32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun readU32(b: ByteArray, o: Int): Long = readI32(b, o).toLong() and 0xFFFFFFFFL

    private fun wrap360(h: Float): Float = ((h % 360f) + 360f) % 360f

    private companion object {
        const val UBX_SYNC1 = 0xB5.toByte()
        const val UBX_SYNC2 = 0x62.toByte()
        const val MAX_FRAME = 1024          // sanity cap for a bad length field
        const val MIN_FIX_SATS = 4          // fix proxy when only POSLLH/VELNED stream
    }
}
