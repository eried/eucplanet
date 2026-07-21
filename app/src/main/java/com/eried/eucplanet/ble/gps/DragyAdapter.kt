package com.eried.eucplanet.ble.gps

import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.ExternalGpsSource
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dragy / Dragy Lite GPS performance-meter adapter.
 *
 * Protocol decoded from the official Dragy Android app (com.dragy 2.30):
 * Dragy uses a CUSTOM GATT profile (NOT Nordic UART) - service 0xFD00, GPS
 * data on notify char 0xFD02, command-write on 0xFD01 (see
 * [ExternalGpsGattProfile.DRAGY]). Over 0xFD02 it streams raw u-blox UBX
 * messages (the app's DragyBleOrigin decodes NAV-PVT 0x01/0x07 for
 * position+speed, NAV-SAT 0x01/0x35, NAV-DOP 0x01/0x04). The firmware auto-
 * streams once 0xFD02 is subscribed; [initCommands] writes the app's
 * `Send10HzData` UBX config to 0xFD01 to pin the rate at 10 Hz.
 *
 * This parses the standard UBX envelope (identical framing to RaceBox) -
 * NAV-PVT is the confirmed message and the primary path; NAV-POSLLH/VELNED
 * are kept as a harmless fallback for any firmware that splits them. The
 * ground-speed conversion matches the app exactly (gSpeed mm/s x 3.6 / 1000
 * = km/h). A tester GATT dump (Z Flip 7, 2026-07-20) confirmed the profile;
 * unhandled UBX / non-UBX bytes are still logged once for safety.
 */
@Singleton
class DragyAdapter @Inject constructor() : ExternalGpsAdapter {

    override val source: ExternalGpsSource = ExternalGpsSource.DRAGY

    /**
     * Advertised-name match. Covers "Dragy", "DRAGY", "Dragy Lite" and the
     * DRG-series model codes (DRG70 / DRG69 / DRG80). Kept Dragy-specific so
     * it can't claim another vendor's box. (A tester's box paired via this.)
     */
    override fun matches(deviceName: String): Boolean {
        val n = deviceName.trim()
        return n.startsWith("Dragy", ignoreCase = true) ||
            n.startsWith("DRG", ignoreCase = true)
    }

    /** Dragy's custom GATT profile (service FD00, notify FD02, write FD01). */
    override fun gattProfile(): ExternalGpsGattProfile = ExternalGpsGattProfile.DRAGY

    /**
     * Battery isn't in the FD02 telemetry stream - the official app READs the
     * device-status characteristic FD04 for it (DragyBleManager.readBattery).
     * So we have the connection manager poll FD04; [onPollResult] decodes it.
     */
    override fun pollCharacteristic(): UUID? = CHARACTER_DEVICE_STATUS

    /** Battery drifts slowly; 30 s is plenty and keeps the radio quiet. */
    override fun pollIntervalMs(): Long = 30_000L

    /**
     * FD04 device-status read. The official app takes the SECOND byte of the
     * response as the battery percent (bytesToHex(...).substring(2,4) parsed as
     * hex, i.e. byte[1]). We stash it and fold it into the next decoded sample.
     * Dragy's byte is percent-only, so [ExternalGpsSample.charging] stays null.
     */
    override fun onPollResult(value: ByteArray) {
        if (value.size < 2) return
        val pct = (value[1].toInt() and 0xFF).coerceIn(0, 100)
        lastBatteryPct = pct
        if (!loggedFirstBattery) {
            loggedFirstBattery = true
            DiagnosticsLogger.note("dragy: battery read FD04 -> $pct%")
        }
    }

    /**
     * Post-subscribe writes to 0xFD01. The Dragy firmware auto-streams UBX on
     * subscribe, so we only pin the output rate to 10 Hz with the app's own
     * `Send10HzData` UBX-CFG frame (verbatim from com.dragy DragyBleOrigin).
     * 10 Hz is plenty for a wheel and lighter on BLE than 20/25 Hz. Dragy
     * doesn't use time/position assistance, so the parameters are ignored.
     */
    override fun initCommands(
        timeUtcMillis: Long,
        lastKnownLat: Double?,
        lastKnownLon: Double?,
        lastKnownAccM: Float?
    ): List<ByteArray> = listOf(hex(SEND_10HZ))

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

    // Battery percent from the most recent FD04 read (see [onPollResult]),
    // folded into every emitted sample. Null until the first read lands.
    @Volatile private var lastBatteryPct: Int? = null
    @Volatile private var loggedFirstBattery = false

    /** UBX (cls,id) combos already logged, so the capture note fires once each. */
    private val seenTypes = HashSet<Int>()
    @Volatile private var loggedFirstPvt = false
    @Volatile private var loggedFirstFix = false

    override fun decode(notification: ByteArray): ExternalGpsSample? {
        if (notification.isEmpty()) return null
        notification.forEach { buffer.addLast(it) }

        var emitted: ExternalGpsSample? = null
        // Drain every complete frame currently in the buffer; keep the last
        // sample produced (samples arrive faster than the UI needs).
        while (true) {
            // Re-sync to the next UBX header (B5 62). Leading non-UBX bytes are
            // normal right after subscribe (we join mid-stream), so we just skip
            // to the next sync without noise.
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
        val numSV = frame[p + 23].toInt() and 0xFF
        // Confirm PVT is flowing and surface the fix state ONCE, so a tester
        // .txt shows "PVT OK, fixType=0" (connected but no fix yet, go outside)
        // vs no PVT line at all (firmware not emitting it).
        if (!loggedFirstPvt) {
            loggedFirstPvt = true
            DiagnosticsLogger.note(
                "dragy: NAV-PVT stream OK, fixType=$fixType numSV=$numSV " +
                    "(fixType 0/1 = no GPS fix yet; needs open sky)"
            )
        }
        if (fixType != 2 && fixType != 3 && fixType != 4) return null
        if (!loggedFirstFix) {
            loggedFirstFix = true
            DiagnosticsLogger.note("dragy: first GPS fix, sats=$numSV")
        }
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
            numSatellites = numSV,
            batteryPercent = lastBatteryPct
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
            numSatellites = sats,
            batteryPercent = lastBatteryPct
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

    /** Hex string -> bytes (for the verbatim UBX command from the Dragy app). */
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    private companion object {
        const val UBX_SYNC1 = 0xB5.toByte()
        const val UBX_SYNC2 = 0x62.toByte()
        const val MAX_FRAME = 1024          // sanity cap for a bad length field
        const val MIN_FIX_SATS = 4          // fix proxy when only POSLLH/VELNED stream
        // UBX-CFG "Send10HzData" verbatim from the official Dragy app
        // (DragyBleOrigin.CmdFeature.Send10HzData): sets 10 Hz output.
        const val SEND_10HZ = "B562068A0A000003000001002130640053CB"
        // FD04 device-status characteristic (DragyUUID.CHARACTER_DEVICE_STATUS);
        // the official app READs it for battery. Same 16-bit base as FD00-FD05.
        val CHARACTER_DEVICE_STATUS: UUID =
            UUID.fromString("0000fd04-0000-1000-8000-00805f9b34fb")
    }
}
