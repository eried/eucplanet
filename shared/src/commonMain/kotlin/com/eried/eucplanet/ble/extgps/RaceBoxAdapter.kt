package com.eried.eucplanet.ble.extgps

import com.eried.eucplanet.data.ExternalGpsAdapter
import com.eried.eucplanet.data.ExternalGpsSample
import com.eried.eucplanet.data.ExternalGpsSource
import kotlin.concurrent.Volatile

/**
 * RaceBox Mini / Mini S / Pro adapter — faithful port of Android's RaceBoxAdapter.
 *
 * BLE: Nordic UART. Streams u-blox UBX frames over notifications, chunked across
 * BLE packets, so we hold a reassembly buffer and emit a sample only when a
 * complete, Fletcher-8-checksummed NAV-PVT frame arrives. Handles both the
 * standard UBX-NAV-PVT (cls 0x01 / id 0x07 / len 92) and RaceBox's extended Data
 * Message (cls 0xFF / id 0x01 / len 80, which also carries accel).
 *
 * NOTE vs Android: the post-connect MGA-INI assist writes (faster cold-start fix)
 * are not ported here yet — they need a UTC civil breakdown the shared module
 * doesn't have a helper for. The receiver still fixes, just slower on first lock.
 */
class RaceBoxAdapter : ExternalGpsAdapter {

    override val source: ExternalGpsSource = ExternalGpsSource.RACEBOX

    override fun matches(deviceName: String): Boolean =
        deviceName.startsWith("RaceBox", ignoreCase = true)

    private val buffer = ArrayDeque<Byte>()

    @Volatile private var gravityInit = false
    @Volatile private var gravityX = 0f
    @Volatile private var gravityY = 0f
    @Volatile private var gravityZ = 0f
    private val gravityAlpha = 0.02f

    private fun stripGravity(rawX: Float, rawY: Float, rawZ: Float): Triple<Float, Float, Float> {
        if (!gravityInit) {
            gravityX = rawX; gravityY = rawY; gravityZ = rawZ; gravityInit = true
        } else {
            gravityX = gravityX * (1 - gravityAlpha) + rawX * gravityAlpha
            gravityY = gravityY * (1 - gravityAlpha) + rawY * gravityAlpha
            gravityZ = gravityZ * (1 - gravityAlpha) + rawZ * gravityAlpha
        }
        return Triple(rawX - gravityX, rawY - gravityY, rawZ - gravityZ)
    }

    override fun decode(notification: ByteArray): ExternalGpsSample? {
        if (notification.isEmpty()) return null
        notification.forEach { buffer.addLast(it) }

        while (buffer.size >= 2 && !(buffer[0] == 0xB5.toByte() && buffer[1] == 0x62.toByte())) {
            buffer.removeFirst()
        }
        if (buffer.size < 8) return null

        val cls = buffer[2].toInt() and 0xFF
        val id = buffer[3].toInt() and 0xFF
        val len = (buffer[4].toInt() and 0xFF) or ((buffer[5].toInt() and 0xFF) shl 8)
        val totalFrameSize = 6 + len + 2
        if (buffer.size < totalFrameSize) return null

        val frame = ByteArray(totalFrameSize) { buffer.removeFirst() }

        val isStandardPvt = cls == 0x01 && id == 0x07 && len == 92
        val isExtendedPvt = cls == 0xFF && id == 0x01 && len == 80
        if (!isStandardPvt && !isExtendedPvt) return null
        if (!checksumValid(frame)) return null

        return if (isExtendedPvt) parseExtendedPvt(frame) else parsePvt(frame)
    }

    private fun checksumValid(frame: ByteArray): Boolean {
        var ckA = 0; var ckB = 0
        for (i in 2 until frame.size - 2) {
            ckA = (ckA + (frame[i].toInt() and 0xFF)) and 0xFF
            ckB = (ckB + ckA) and 0xFF
        }
        return ckA == (frame[frame.size - 2].toInt() and 0xFF) &&
            ckB == (frame[frame.size - 1].toInt() and 0xFF)
    }

    private fun parsePvt(frame: ByteArray): ExternalGpsSample? {
        val p = 6
        if (frame.size < p + 92) return null
        val fixType = frame[p + 20].toInt() and 0xFF
        if (fixType != 2 && fixType != 3 && fixType != 4) return null

        val numSV = frame[p + 23].toInt() and 0xFF
        val lonRaw = readInt32LE(frame, p + 24)
        val latRaw = readInt32LE(frame, p + 28)
        val hMslRaw = readInt32LE(frame, p + 36)
        val hAccRaw = readUInt32LE(frame, p + 40)
        val velDRaw = readInt32LE(frame, p + 56)
        val gSpeedRaw = readInt32LE(frame, p + 60)
        val headMotRaw = readInt32LE(frame, p + 64)

        return ExternalGpsSample(
            source = ExternalGpsSource.RACEBOX,
            speedKmh = gSpeedRaw.coerceAtLeast(0) * 0.0036f,
            latitude = latRaw * 1e-7,
            longitude = lonRaw * 1e-7,
            altitudeMeters = hMslRaw / 1000f,
            accuracyMeters = hAccRaw / 1000f,
            headingDeg = (headMotRaw * 1e-5f).let { h -> ((h % 360f) + 360f) % 360f },
            verticalSpeedMps = -velDRaw / 1000f,
            numSatellites = numSV,
        )
    }

    private fun parseExtendedPvt(frame: ByteArray): ExternalGpsSample? {
        val p = 6
        if (frame.size < p + 80) return null
        val fixType = frame[p + 20].toInt() and 0xFF
        if (fixType != 2 && fixType != 3 && fixType != 4) return null

        val numSV = frame[p + 23].toInt() and 0xFF
        val lonRaw = readInt32LE(frame, p + 24)
        val latRaw = readInt32LE(frame, p + 28)
        val hMslRaw = readInt32LE(frame, p + 36)
        val hAccRaw = readUInt32LE(frame, p + 40)
        val gSpeedRaw = readInt32LE(frame, p + 48)
        val headMotRaw = readInt32LE(frame, p + 52)
        val rawX = readInt16LE(frame, p + 68) / 1000f
        val rawY = readInt16LE(frame, p + 70) / 1000f
        val rawZ = readInt16LE(frame, p + 72) / 1000f
        val (ax, ay, az) = stripGravity(rawX, rawY, rawZ)

        return ExternalGpsSample(
            source = ExternalGpsSource.RACEBOX,
            speedKmh = gSpeedRaw.coerceAtLeast(0) * 0.0036f,
            latitude = latRaw * 1e-7,
            longitude = lonRaw * 1e-7,
            altitudeMeters = hMslRaw / 1000f,
            accuracyMeters = hAccRaw / 1000f,
            accelXG = ax, accelYG = ay, accelZG = az,
            headingDeg = (headMotRaw * 1e-5f).let { h -> ((h % 360f) + 360f) % 360f },
            verticalSpeedMps = 0f,
            numSatellites = numSV,
        )
    }

    private fun readInt32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readInt16LE(b: ByteArray, off: Int): Int {
        val v = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        return if (v and 0x8000 != 0) v or 0x7FFF.inv() else v
    }

    private fun readUInt32LE(b: ByteArray, off: Int): Long =
        readInt32LE(b, off).toLong() and 0xFFFFFFFFL
}
