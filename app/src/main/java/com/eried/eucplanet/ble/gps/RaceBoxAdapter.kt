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

        // Only NAV-PVT carries position+speed; ignore other frames.
        if (cls != 0x01 || id != 0x07 || len != 92) return null

        // Fletcher-8 checksum over class, id, length, payload (everything after the
        // header bytes, before the trailing checksum bytes).
        if (!checksumValid(frame)) return null

        return parsePvt(frame)
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

        val lonRaw = readInt32LE(frame, payloadStart + 24)
        val latRaw = readInt32LE(frame, payloadStart + 28)
        val hMslRaw = readInt32LE(frame, payloadStart + 36)
        val hAccRaw = readUInt32LE(frame, payloadStart + 40)
        val gSpeedRaw = readInt32LE(frame, payloadStart + 60)

        val speedMmS = gSpeedRaw.coerceAtLeast(0)
        return ExternalGpsSample(
            source = ExternalGpsSource.RACEBOX,
            speedKmh = speedMmS * 0.0036f,                // mm/s → km/h
            latitude = latRaw * 1e-7,                     // deg
            longitude = lonRaw * 1e-7,                    // deg
            altitudeMeters = hMslRaw / 1000f,             // mm → m
            accuracyMeters = hAccRaw / 1000f              // mm → m
        )
    }

    private fun readInt32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readUInt32LE(b: ByteArray, off: Int): Long =
        readInt32LE(b, off).toLong() and 0xFFFFFFFFL
}
