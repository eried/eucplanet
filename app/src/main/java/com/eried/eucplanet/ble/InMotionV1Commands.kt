package com.eried.eucplanet.ble

import com.eried.eucplanet.ble.InMotionV1Protocol.CanId
import com.eried.eucplanet.util.ByteUtils

/**
 * Outbound command builders for the InMotion V1 BLE protocol. Each command is
 * an 8-byte CAN payload wrapped by [InMotionV1Protocol.buildFrame] in the
 * `AA AA … 55 55` envelope with `0xA5` byte stuffing and a sum-mod-256
 * checksum applied. See docs/protocols/inmotion_v1.md section 6 for the table.
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android — GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
object InMotionV1Commands {

    private val ALL_FF = ByteArray(8) { 0xFF.toByte() }

    // --- Telemetry queries ---

    /** Fast info poll: data filled with 0xFF, normal data frame. */
    fun getFastInfo(): ByteArray =
        InMotionV1Protocol.buildFrame(CanId.FAST_INFO, ALL_FF)

    /** Slow info / settings dump: same data, but sent as a remote-frame request. */
    fun getSlowInfo(): ByteArray =
        InMotionV1Protocol.buildFrame(CanId.SLOW_INFO, ALL_FF, remote = true)

    // --- Lighting ---

    fun setLight(on: Boolean): ByteArray =
        InMotionV1Protocol.buildFrame(
            CanId.HEADLIGHT,
            byteArrayOf(if (on) 0x01 else 0x00, 0, 0, 0, 0, 0, 0, 0)
        )

    /** Decorative under-glow LED — V8 family / V10 family / Glide 3 only. */
    fun setDRL(on: Boolean): ByteArray =
        InMotionV1Protocol.buildFrame(
            CanId.REMOTE_CTRL,
            byteArrayOf(0xB2.toByte(), 0, 0, 0, if (on) 0x0F else 0x10, 0, 0, 0)
        )

    // --- Audio ---

    /** Dedicated horn opcode used by V8F / V8S / V10 family / Glide 3. */
    fun hornDedicated(): ByteArray =
        InMotionV1Protocol.buildFrame(
            CanId.REMOTE_CTRL,
            byteArrayOf(0xB2.toByte(), 0, 0, 0, 0x11, 0, 0, 0)
        )

    /** Legacy horn for V8 / V5 / R-series / V3 / L6 — plays sound 4 instead. */
    fun hornLegacy(): ByteArray = playSound(4)

    fun playSound(index: Int): ByteArray =
        InMotionV1Protocol.buildFrame(
            CanId.PLAY_SOUND,
            byteArrayOf((index and 0xFF).toByte(), 0, 0, 0, 0, 0, 0, 0)
        )

    /** Speaker volume — V8F / V8S / V10 family / Glide 3 only. */
    fun setVolume(percent: Int): ByteArray {
        val v = ByteUtils.putUint16LE((percent.coerceIn(0, 100) * 100))
        return InMotionV1Protocol.buildFrame(
            CanId.VOLUME,
            byteArrayOf(v[0], v[1], 0, 0, 0, 0, 0, 0)
        )
    }

    // --- Ride mode group (CAN 0x0F550115) ---

    /**
     * Set max speed (tiltback). Encoded as `(kmh * 1000)` u16 with HIGH byte
     * at slot 4 and LOW byte at slot 5 — opposite of the rest of the
     * protocol's little-endian convention. Spec section 6.2 worked example
     * is unambiguous: 30 km/h -> 30000 = 0x7530, wire = `75 30`. The spec's
     * type annotation says `LE` but the worked example takes precedence
     * here. Verify against a real BLE capture if behaviour looks wrong.
     */
    fun setMaxSpeed(kmh: Float): ByteArray {
        val v = (kmh * 1000f).toInt() and 0xFFFF
        val hi = ((v ushr 8) and 0xFF).toByte()
        val lo = (v and 0xFF).toByte()
        return InMotionV1Protocol.buildFrame(
            CanId.RIDE_MODE,
            byteArrayOf(0x01, 0, 0, 0, hi, lo, 0, 0)
        )
    }

    /** Ride mode: 1 = classic, 0 = comfort. V8F / V8S / V10 family only. */
    fun setRideMode(classic: Boolean): ByteArray =
        InMotionV1Protocol.buildFrame(
            CanId.RIDE_MODE,
            byteArrayOf(0x0A, 0, 0, 0, if (classic) 0x01 else 0x00, 0, 0, 0)
        )

    /**
     * Pedal sensitivity 0..255. Slot 4..5 stores `((s + 28) << 5)` with the
     * same HIGH-then-LOW byte order as [setMaxSpeed] (spec section 6.3
     * worked example).
     */
    fun setPedalSensitivity(sensitivity: Int): ByteArray {
        val s = sensitivity.coerceIn(0, 255)
        val v = ((s + 28) shl 5) and 0xFFFF
        val hi = ((v ushr 8) and 0xFF).toByte()
        val lo = (v and 0xFF).toByte()
        return InMotionV1Protocol.buildFrame(
            CanId.RIDE_MODE,
            byteArrayOf(0x06, 0, 0, 0, hi, lo, 0, 0)
        )
    }

    /** Pedal tilt (horizon) in tenths of a degree — encoded as `(deg10 * 65536 / 10)` LE. */
    fun setPedalTilt(deg10: Int): ByteArray {
        val raw = ((deg10.toLong() * 65536L) / 10L).toInt()
        val b = ByteArray(4)
        b[0] = (raw and 0xFF).toByte()
        b[1] = ((raw ushr 8) and 0xFF).toByte()
        b[2] = ((raw ushr 16) and 0xFF).toByte()
        b[3] = ((raw ushr 24) and 0xFF).toByte()
        return InMotionV1Protocol.buildFrame(
            CanId.RIDE_MODE,
            byteArrayOf(0, 0, 0, 0, b[0], b[1], b[2], b[3])
        )
    }

    // --- Remote control group (CAN 0x0F550116) ---

    fun powerOff(): ByteArray =
        InMotionV1Protocol.buildFrame(
            CanId.REMOTE_CTRL,
            byteArrayOf(0xB2.toByte(), 0, 0, 0, 0x05, 0, 0, 0)
        )

    fun calibrate(): ByteArray =
        InMotionV1Protocol.buildFrame(
            CanId.CALIBRATE,
            byteArrayOf(0x32, 0x54, 0x76, 0x98.toByte(), 0, 0, 0, 0)
        )

    /** Handle / lift sensor button: 0x00 enables, 0x01 disables. */
    fun setHandleButton(enabled: Boolean): ByteArray =
        InMotionV1Protocol.buildFrame(
            CanId.HANDLE_BTN,
            byteArrayOf(if (enabled) 0x00 else 0x01, 0, 0, 0, 0, 0, 0, 0)
        )

    // --- Auth (PIN handshake) ---

    /**
     * 6-digit PIN response per spec section 7. Wire format is the 6 ASCII
     * digits followed by two zero bytes, sent on CAN ID `0x0F550307` as a
     * normal data frame. Wheels without a PIN configured ignore this.
     */
    fun sendPin(pin: String): ByteArray {
        require(pin.length == 6 && pin.all { it.isDigit() }) {
            "InMotion V1 PIN must be exactly 6 digits"
        }
        val data = ByteArray(8)
        for (i in 0 until 6) data[i] = pin[i].code.toByte()
        return InMotionV1Protocol.buildFrame(CanId.PIN, data)
    }
}
