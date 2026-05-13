package com.eried.eucplanet.ble.gps

import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.ExternalGpsSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RaceBox Mini / Mini S / Pro adapter.
 *
 * BLE: Nordic UART (same UUID as InMotion V14 — that's why scanner matching is
 * name-based). Devices advertise as "RaceBox Mini 1234567" etc.
 *
 * Streamed protocol: u-blox UBX frames over BLE notifications. Each frame is
 * `B5 62 (class) (id) (len_lo) (len_hi) (payload...) (ck_a) (ck_b)`.
 * Class 0x01 / id 0x07 = NAV-PVT, 92-byte payload, total frame 100 bytes.
 * BLE chunks the frame across multiple notifications (typical MTU 23/244),
 * so the adapter holds a reassembly buffer and emits a sample only when a
 * complete, checksummed NAV-PVT frame has been received.
 *
 * Reference: u-blox protocol spec UBX-NAV-PVT, RaceBox public BLE notes
 * (github.com/Racebox/racebox-public).
 */
@Singleton
class RaceBoxAdapter @Inject constructor() : ExternalGpsAdapter {

    override val source: ExternalGpsSource = ExternalGpsSource.RACEBOX

    override fun matches(deviceName: String): Boolean =
        deviceName.startsWith("RaceBox", ignoreCase = true)

    /** Rolling buffer of raw notification bytes; UBX frames may straddle chunks. */
    private val buffer = ArrayDeque<Byte>()

    // Running estimate of the gravity vector in the device's own frame. The
    // RaceBox accel reading is raw — gravity is included — so when the box is
    // mounted at any angle, e.g. lying flat or strapped to a wheel pedal, that
    // 1 g of gravity decomposes across two or three axes and is read as a
    // static 0.5–1.0 g offset. The phone's TYPE_LINEAR_ACCELERATION already
    // subtracts gravity internally, so we mirror that here with a slow EWMA
    // (alpha = 0.02 ≈ 5-second time constant at 10 Hz) and subtract before we
    // hand the values to the UI. The first sample seeds the estimate so we
    // don't ship one "0 g everywhere" outlier on connect.
    @Volatile private var gravityInit = false
    @Volatile private var gravityX = 0f
    @Volatile private var gravityY = 0f
    @Volatile private var gravityZ = 0f
    private val gravityAlpha = 0.02f

    private fun stripGravity(rawX: Float, rawY: Float, rawZ: Float): Triple<Float, Float, Float> {
        if (!gravityInit) {
            gravityX = rawX
            gravityY = rawY
            gravityZ = rawZ
            gravityInit = true
        } else {
            gravityX = gravityX * (1 - gravityAlpha) + rawX * gravityAlpha
            gravityY = gravityY * (1 - gravityAlpha) + rawY * gravityAlpha
            gravityZ = gravityZ * (1 - gravityAlpha) + rawZ * gravityAlpha
        }
        return Triple(rawX - gravityX, rawY - gravityY, rawZ - gravityZ)
    }

    override fun decode(notification: ByteArray): ExternalGpsSample? {
        if (notification.isEmpty()) return null
        // Append to the rolling buffer, then try to extract one complete frame.
        // The first sample wins per notification; if the buffer somehow holds
        // more than one frame we'll pick the next on the following notification.
        notification.forEach { buffer.addLast(it) }

        // Re-sync: drop bytes until the buffer starts with a UBX header.
        while (buffer.size >= 2 && !(buffer[0] == 0xB5.toByte() && buffer[1] == 0x62.toByte())) {
            buffer.removeFirst()
        }
        if (buffer.size < 8) return null  // not enough for header+class+id+length

        val cls = buffer[2].toInt() and 0xFF
        val id = buffer[3].toInt() and 0xFF
        val len = (buffer[4].toInt() and 0xFF) or ((buffer[5].toInt() and 0xFF) shl 8)
        val totalFrameSize = 6 + len + 2
        if (buffer.size < totalFrameSize) return null  // wait for more chunks

        // Pull the candidate frame out of the buffer.
        val frame = ByteArray(totalFrameSize) { buffer.removeFirst() }

        // Accept either:
        //  - Standard UBX NAV-PVT (cls=0x01, id=0x07, len=92): position+speed only.
        //  - RaceBox extended (cls=0xFF, id=0x01, len=80): position+speed PLUS
        //    accelerometer X/Y/Z and gyro at the tail. Devices stream this when
        //    the user enables data recording on the RaceBox companion.
        val isStandardPvt = cls == 0x01 && id == 0x07 && len == 92
        val isExtendedPvt = cls == 0xFF && id == 0x01 && len == 80
        if (!isStandardPvt && !isExtendedPvt) return null

        // Fletcher-8 checksum over class, id, length, payload.
        if (!checksumValid(frame)) return null

        return if (isExtendedPvt) parseExtendedPvt(frame) else parsePvt(frame)
    }

    private fun checksumValid(frame: ByteArray): Boolean {
        var ckA = 0
        var ckB = 0
        for (i in 2 until frame.size - 2) {
            ckA = (ckA + (frame[i].toInt() and 0xFF)) and 0xFF
            ckB = (ckB + ckA) and 0xFF
        }
        val expectedA = frame[frame.size - 2].toInt() and 0xFF
        val expectedB = frame[frame.size - 1].toInt() and 0xFF
        return ckA == expectedA && ckB == expectedB
    }

    /**
     * UBX-NAV-PVT payload offsets (relative to start of payload, which is byte 6
     * of the frame). Only fields we surface are extracted.
     *  20 fixType   (uint8)   0 no fix, 2 2D, 3 3D, 4 GNSS+dead-reckoning, 5 time-only
     *  23 numSV     (uint8)   satellites used
     *  24 lon       (int32 LE) deg * 1e-7
     *  28 lat       (int32 LE) deg * 1e-7
     *  36 hMSL      (int32 LE) mm above mean sea level
     *  40 hAcc      (uint32 LE) horizontal accuracy in mm
     *  60 gSpeed    (int32 LE) ground speed in mm/s
     */
    private fun parsePvt(frame: ByteArray): ExternalGpsSample? {
        val payloadStart = 6
        if (frame.size < payloadStart + 92) return null

        val fixType = frame[payloadStart + 20].toInt() and 0xFF
        // 0 = no fix, 1 = dead-reckoning only, 5 = time-only — none of these
        // are useful as a position sample. Drop and let the next frame land.
        if (fixType != 2 && fixType != 3 && fixType != 4) return null

        val numSV = frame[payloadStart + 23].toInt() and 0xFF
        val lonRaw = readInt32LE(frame, payloadStart + 24)
        val latRaw = readInt32LE(frame, payloadStart + 28)
        val hMslRaw = readInt32LE(frame, payloadStart + 36)
        val hAccRaw = readUInt32LE(frame, payloadStart + 40)
        // NED vertical velocity, positive = down. Flip sign so the field
        // reads "+up" consistent with phone Location.verticalAccuracyMeters
        // semantics elsewhere.
        val velDRaw = readInt32LE(frame, payloadStart + 56)
        val gSpeedRaw = readInt32LE(frame, payloadStart + 60)
        val headMotRaw = readInt32LE(frame, payloadStart + 64)

        val speedMmS = gSpeedRaw.coerceAtLeast(0)
        return ExternalGpsSample(
            source = ExternalGpsSource.RACEBOX,
            speedKmh = speedMmS * 0.0036f,                // mm/s → km/h
            latitude = latRaw * 1e-7,                     // deg
            longitude = lonRaw * 1e-7,                    // deg
            altitudeMeters = hMslRaw / 1000f,             // mm → m
            accuracyMeters = hAccRaw / 1000f,             // mm → m
            headingDeg = (headMotRaw * 1e-5f).let { h ->
                // Wrap to [0, 360); the wire format can carry small negative
                // values when heading dithers near zero.
                ((h % 360f) + 360f) % 360f
            },
            verticalSpeedMps = -velDRaw / 1000f,          // mm/s down → m/s up
            numSatellites = numSV
        )
    }

    /**
     * RaceBox extended Data Message (cls=0xFF id=0x01, 80-byte payload). This
     * is RaceBox's own format, NOT a straight extension of UBX-NAV-PVT — the
     * field layout differs after offset 36 and we have to read RaceBox's
     * payload-relative offsets directly. An earlier revision of this parser
     * assumed PVT compatibility and read speed from offset 60, which actually
     * lands on `headingAccuracy` (a u32 of heading uncertainty in 1e-5°).
     * Typical heading-uncertainty values gave reported speeds of 100-200 mph
     * when the rider was at 34 mph — exact symptom we saw in the wild.
     *
     * Payload-relative offsets (verified against the RaceBox public protocol
     * and cross-checked against the ESP32-RaceBox open-source client):
     *  20 fixStatus           uint8   (0 none, 2 2D, 3 3D)
     *  23 numSVs              uint8
     *  24 longitude           int32 LE  deg × 1e-7
     *  28 latitude            int32 LE  deg × 1e-7
     *  32 wgsAltitude         int32 LE  mm
     *  36 mslAltitude         int32 LE  mm
     *  40 horizontalAccuracy  uint32 LE mm
     *  44 verticalAccuracy    uint32 LE mm
     *  48 speed               int32 LE  mm/s  ← was wrongly read at 60
     *  52 heading             int32 LE  deg × 1e-5  ← was wrongly read at 64
     *  56 speedAccuracy       uint32 LE mm/s
     *  60 headingAccuracy     uint32 LE deg × 1e-5
     *  64 pDOP                uint16 LE × 0.01
     *  66 latLonFlags         uint8
     *  67 batteryStatus       uint8
     *  68 gForceX             int16 LE  mg
     *  70 gForceY             int16 LE  mg
     *  72 gForceZ             int16 LE  mg
     *  74 rotRateX/Y/Z        int16 LE  (gyro — not surfaced yet)
     *
     * Vertical speed isn't in this frame at all; the UBX-NAV-PVT velD field
     * is absent. We leave verticalSpeedMps at 0 here; clients that need it
     * have to switch to the standard UBX-NAV-PVT stream (cls=0x01 id=0x07).
     */
    private fun parseExtendedPvt(frame: ByteArray): ExternalGpsSample? {
        val payloadStart = 6
        if (frame.size < payloadStart + 80) return null

        val fixType = frame[payloadStart + 20].toInt() and 0xFF
        if (fixType != 2 && fixType != 3 && fixType != 4) return null

        val numSV = frame[payloadStart + 23].toInt() and 0xFF
        val lonRaw = readInt32LE(frame, payloadStart + 24)
        val latRaw = readInt32LE(frame, payloadStart + 28)
        val hMslRaw = readInt32LE(frame, payloadStart + 36)
        val hAccRaw = readUInt32LE(frame, payloadStart + 40)
        val gSpeedRaw = readInt32LE(frame, payloadStart + 48)
        val headMotRaw = readInt32LE(frame, payloadStart + 52)

        // Accel: int16 LE milli-g per axis. Divide by 1000 to get g, then
        // remove the gravity component so the values are comparable with the
        // phone's TYPE_LINEAR_ACCELERATION reading.
        val rawX = readInt16LE(frame, payloadStart + 68) / 1000f
        val rawY = readInt16LE(frame, payloadStart + 70) / 1000f
        val rawZ = readInt16LE(frame, payloadStart + 72) / 1000f
        val (ax, ay, az) = stripGravity(rawX, rawY, rawZ)

        val speedMmS = gSpeedRaw.coerceAtLeast(0)
        return ExternalGpsSample(
            source = ExternalGpsSource.RACEBOX,
            speedKmh = speedMmS * 0.0036f,
            latitude = latRaw * 1e-7,
            longitude = lonRaw * 1e-7,
            altitudeMeters = hMslRaw / 1000f,
            accuracyMeters = hAccRaw / 1000f,
            accelXG = ax,
            accelYG = ay,
            accelZG = az,
            headingDeg = (headMotRaw * 1e-5f).let { h ->
                ((h % 360f) + 360f) % 360f
            },
            verticalSpeedMps = 0f,
            numSatellites = numSV
        )
    }

    private fun readInt32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readInt16LE(b: ByteArray, off: Int): Int {
        val v = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        return if (v and 0x8000 != 0) v or 0x7FFF.inv() else v
    }

    private fun readUInt32LE(b: ByteArray, off: Int): Long =
        readInt32LE(b, off).toLong() and 0xFFFFFFFFL
}
