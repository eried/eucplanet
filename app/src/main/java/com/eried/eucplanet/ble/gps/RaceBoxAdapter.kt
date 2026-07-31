package com.eried.eucplanet.ble.gps

import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.ExternalGpsSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RaceBox Mini / Mini S / Pro adapter.
 *
 * BLE: Nordic UART (same UUID as InMotion V14, which is why scanner matching is
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
    // RaceBox accel reading is raw (gravity is included), so when the box is
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
        // 0 = no fix, 1 = dead-reckoning only, 5 = time-only: none of these
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
     * is RaceBox's own format, NOT a straight extension of UBX-NAV-PVT: the
     * field layout differs after offset 36 and we have to read RaceBox's
     * payload-relative offsets directly. An earlier revision of this parser
     * assumed PVT compatibility and read speed from offset 60, which actually
     * lands on `headingAccuracy` (a u32 of heading uncertainty in 1e-5°).
     * Typical heading-uncertainty values gave reported speeds of 100-200 mph
     * when the rider was at 34 mph; exact symptom we saw in the wild.
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
     *  74 rotRateX/Y/Z        int16 LE  (gyro, not surfaced yet)
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

        // batteryStatus (offset 67): bit 7 = charging flag, bits 0-6 = battery
        // level in %. (RaceBox Micro repurposes this byte as input voltage x10;
        // we decode the Mini/Mini S meaning, which is what a wheel rider uses.)
        val batteryRaw = frame[payloadStart + 67].toInt() and 0xFF
        val charging = (batteryRaw and 0x80) != 0
        val batteryPct = (batteryRaw and 0x7F).coerceIn(0, 100)

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
            numSatellites = numSV,
            batteryPercent = batteryPct,
            charging = charging
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

    // ---- Post-connect MGA-INI assistance writes ----
    //
    // Mirrors the official RaceBox companion app's post-subscribe sequence.
    // Without these, the u-blox receiver inside the RaceBox does a full
    // cold-start search and a fix can take a minute+. Pushing INI-TIME +
    // INI-POS doesn't deliver ephemeris (that needs AssistNow Online), but
    // it tells the receiver where and when it is so it can pick which
    // satellites to acquire instead of brute-forcing the sky.

    override fun initCommands(
        timeUtcMillis: Long,
        lastKnownLat: Double?,
        lastKnownLon: Double?,
        lastKnownAccM: Float?
    ): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        out += buildMgaIniTimeUtc(timeUtcMillis)
        if (lastKnownLat != null && lastKnownLon != null) {
            out += buildMgaIniPosLlh(
                latDeg = lastKnownLat,
                lonDeg = lastKnownLon,
                altCm = 0,
                posAccCm = ((lastKnownAccM ?: 100f) * 100f).toLong().coerceIn(100, 1_000_000)
            )
        }
        return out
    }

    /**
     * UBX-MGA-INI-TIME_UTC (class 0x13, id 0x40, type 0x10) per u-blox spec.
     * Payload (24 bytes):
     *  0  type       = 0x10
     *  1  version    = 0x00
     *  2  ref        = 0x00 (NONE, anchor the time to "now")
     *  3  leapSecs   = -128 (0x80) if unknown, else signed delta from GPS
     *  4-5  year     (u16 LE)
     *  6    month
     *  7    day
     *  8    hour
     *  9    minute
     *  10   second
     *  11   reserved
     *  12-15 ns          (i32 LE) sub-second nanoseconds
     *  16-19 tAccS       (u32 LE) time accuracy estimate, whole seconds
     *  20-23 tAccNs      (u32 LE) time accuracy estimate, fractional ns
     */
    private fun buildMgaIniTimeUtc(timeUtcMillis: Long): ByteArray {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = timeUtcMillis
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val second = cal.get(java.util.Calendar.SECOND)
        val ns = (cal.get(java.util.Calendar.MILLISECOND) * 1_000_000)
        val payload = ByteArray(24)
        payload[0] = 0x10
        payload[1] = 0x00
        payload[2] = 0x00
        // leapSecs = 18 (current GPS-UTC offset as of 2017-01-01, still in effect)
        payload[3] = 18
        writeU16LE(payload, 4, year)
        payload[6] = month.toByte()
        payload[7] = day.toByte()
        payload[8] = hour.toByte()
        payload[9] = minute.toByte()
        payload[10] = second.toByte()
        // payload[11] reserved -> 0
        writeI32LE(payload, 12, ns)
        // tAccS = 10 (phone wall clock is typically accurate to <1s but let's
        // give the receiver headroom); tAccNs left at 0.
        writeI32LE(payload, 16, 10)
        writeI32LE(payload, 20, 0)
        return wrapUbx(classId = 0x13, msgId = 0x40, payload = payload)
    }

    /**
     * UBX-MGA-INI-POS_LLH (class 0x13, id 0x40, type 0x01) per u-blox spec.
     * Payload (20 bytes):
     *  0   type = 0x01
     *  1   version = 0x00
     *  2-3 reserved
     *  4-7 lat   (i32 LE)   deg × 1e-7
     *  8-11 lon  (i32 LE)   deg × 1e-7
     *  12-15 alt (i32 LE)   cm above MSL
     *  16-19 posAcc (u32 LE) cm horizontal 1-sigma accuracy
     */
    private fun buildMgaIniPosLlh(
        latDeg: Double,
        lonDeg: Double,
        altCm: Int,
        posAccCm: Long
    ): ByteArray {
        val payload = ByteArray(20)
        payload[0] = 0x01
        payload[1] = 0x00
        // bytes 2..3 reserved
        writeI32LE(payload, 4, (latDeg * 1e7).toInt())
        writeI32LE(payload, 8, (lonDeg * 1e7).toInt())
        writeI32LE(payload, 12, altCm)
        writeI32LE(payload, 16, posAccCm.toInt())
        return wrapUbx(classId = 0x13, msgId = 0x40, payload = payload)
    }

    /** Wrap a payload in a complete UBX frame: B5 62, class, id, lenLE, payload, ckA, ckB. */
    private fun wrapUbx(classId: Int, msgId: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(6 + payload.size + 2)
        frame[0] = 0xB5.toByte()
        frame[1] = 0x62
        frame[2] = classId.toByte()
        frame[3] = msgId.toByte()
        frame[4] = (payload.size and 0xFF).toByte()
        frame[5] = ((payload.size ushr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 6, payload.size)
        var ckA = 0
        var ckB = 0
        for (i in 2 until frame.size - 2) {
            ckA = (ckA + (frame[i].toInt() and 0xFF)) and 0xFF
            ckB = (ckB + ckA) and 0xFF
        }
        frame[frame.size - 2] = ckA.toByte()
        frame[frame.size - 1] = ckB.toByte()
        return frame
    }

    private fun writeU16LE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun writeI32LE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
