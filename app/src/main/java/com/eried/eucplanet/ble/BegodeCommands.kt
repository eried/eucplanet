package com.eried.eucplanet.ble

/**
 * Outbound command builders for Begode/Gotway wheels.
 *
 * Begode has no envelope: every command is a 1..3 byte ASCII string written
 * with WRITE_NO_RESPONSE to the FFE1 characteristic, no checksum, no ack.
 * Confirmation only arrives via the next 0x04 (Live B) telemetry frame.
 *
 * Spec: docs/protocols/begode.md sections 6.1, 6.2. Protocol research credit:
 * WheelLog (Ilya Shkolnik and contributors, GPLv3), used as a protocol
 * reference; the implementation here is original.
 */
object BegodeCommands {

    // Single-byte ASCII commands (spec 6.1).
    fun horn(): ByteArray = byteArrayOf('b'.code.toByte())

    // Spec uses E/Q/T for off/on/strobe; the user-facing ticket calls the
    // toggle "light cycle" which historically maps to a single byte 'l' on
    // some firmwares. Stock Begode uses the explicit E/Q/T variants, and those
    // are what we send so all FW revisions react.
    fun lightOff(): ByteArray = byteArrayOf('E'.code.toByte())
    fun lightOn(): ByteArray = byteArrayOf('Q'.code.toByte())
    fun lightStrobe(): ByteArray = byteArrayOf('T'.code.toByte())

    fun rollAngleLow(): ByteArray = byteArrayOf('>'.code.toByte())
    fun rollAngleMedium(): ByteArray = byteArrayOf('='.code.toByte())
    fun rollAngleHigh(): ByteArray = byteArrayOf('<'.code.toByte())

    fun pedalsHard(): ByteArray = byteArrayOf('h'.code.toByte())
    fun pedalsMedium(): ByteArray = byteArrayOf('f'.code.toByte())
    fun pedalsSoft(): ByteArray = byteArrayOf('s'.code.toByte())

    fun unitsKm(): ByteArray = byteArrayOf('g'.code.toByte())
    fun unitsMiles(): ByteArray = byteArrayOf('m'.code.toByte())

    // Calibration is a two-step dance; the adapter is responsible for the
    // ~300 ms gap between them per spec 6.1. We just expose both bytes.
    fun calibrationStart(): ByteArray = byteArrayOf('c'.code.toByte())
    fun calibrationConfirm(): ByteArray = byteArrayOf('y'.code.toByte())

    fun powerOff(): ByteArray = byteArrayOf('V'.code.toByte())

    fun beepTest(): ByteArray = byteArrayOf('b'.code.toByte())

    fun disableMaxSpeed(): ByteArray = byteArrayOf('"'.code.toByte())

    /**
     * Set max speed via the W-prefix sub-menu. Spec 6.2: send `W`, then `Y`,
     * then two ASCII digits encoding tens/units, then `b` to confirm. The
     * adapter must space the writes ~100/200 ms apart; we return the four
     * bytes in dispatch order and let the writer pace them.
     *
     * Speed clamped to 0..99 km/h since the wire format is two ASCII digits.
     */
    fun setMaxSpeed(kmh: Int): List<ByteArray> {
        val clamped = kmh.coerceIn(0, 99)
        val high = ((clamped / 10) + 0x30).toByte()
        val low = ((clamped % 10) + 0x30).toByte()
        return listOf(
            byteArrayOf('W'.code.toByte()),
            byteArrayOf('Y'.code.toByte()),
            byteArrayOf(high, low),
            byteArrayOf('b'.code.toByte())
        )
    }

    /**
     * Diagnostic variant of [setMaxSpeed] that concatenates the entire
     * `W Y H L b` sequence into a single 5-byte write. Spec 6.2 says the
     * wheel expects ~100-200 ms of spacing between the four logical steps;
     * this single-write form is for Service Mode probing only, so the user
     * can see whether the connected firmware tolerates an unspaced sequence
     * (newer Begode FW does; older units may silently ignore the request).
     * The next 0x04 Live B frame's max-speed field at offset 10-11 is the
     * authoritative ack.
     *
     * Speed clamped to 0..99 km/h since the wire format is two ASCII digits.
     */
    fun setMaxSpeedSingleWrite(kmh: Int): ByteArray {
        val clamped = kmh.coerceIn(0, 99)
        val high = ((clamped / 10) + 0x30).toByte()
        val low = ((clamped % 10) + 0x30).toByte()
        return byteArrayOf(
            'W'.code.toByte(),
            'Y'.code.toByte(),
            high,
            low,
            'b'.code.toByte()
        )
    }

    /**
     * Set beeper volume 1..9 via the W-prefix sub-menu. Spec 6.2.
     * Returns null for out-of-range values so the caller can no-op cleanly.
     */
    fun setBeeperVolume(level: Int): List<ByteArray>? {
        if (level !in 1..9) return null
        return listOf(
            byteArrayOf('W'.code.toByte()),
            byteArrayOf('B'.code.toByte()),
            byteArrayOf((level + 0x30).toByte())
        )
    }

    /** Set LED mode 0..9 via the W-prefix sub-menu. Spec 6.2. */
    fun setLedMode(mode: Int): List<ByteArray>? {
        if (mode !in 0..9) return null
        return listOf(
            byteArrayOf('W'.code.toByte()),
            byteArrayOf('M'.code.toByte()),
            byteArrayOf((mode + 0x30).toByte())
        )
    }

    /** Request firmware-banner ASCII reply (`GW`/`JN`/`CF`/`BF`...). Spec 6.1. */
    fun queryFirmware(): ByteArray = byteArrayOf('V'.code.toByte())

    /** Request model-name ASCII reply (`NAME ...`). Spec 6.1. */
    fun queryModelName(): ByteArray = byteArrayOf('N'.code.toByte())
}
