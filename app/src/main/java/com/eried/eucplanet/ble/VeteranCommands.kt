package com.eried.eucplanet.ble

/**
 * Outbound command builders for the Veteran (LeaperKim) BLE protocol.
 * Commands are mostly short ASCII writes to the same 0xFFE1 characteristic
 * the wheel notifies on. The horn is the only non-ASCII command — a 14-byte
 * binary blob whose internal structure is partially understood (see spec).
 *
 * Spec: docs/protocols/veteran.md section 6.
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android — GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
object VeteranCommands {

    /**
     * Horn for `model >= 3` (Sherman S, Patton, Lynx, Abrams, Oryx, Nosfet).
     * The blob is byte-for-byte what the wheel expects; bytes 4..13 are not
     * publicly understood (possibly a session tag or feature-negotiation
     * stub) but replay works in practice. Sherman / pre-2020 firmwares
     * accept the legacy single-byte `b` instead — not exposed here because
     * we can't reliably tell them apart before the first telemetry frame
     * lands. The blob is silently ignored on `model < 3`, which is the
     * safer default until we wire model-aware horn dispatch.
     *
     * Spec: docs/protocols/veteran.md section 6, "Beep (firmware model >= 3)".
     */
    private val HORN_BLOB_V3: ByteArray = byteArrayOf(
        0x4C, 0x6B, 0x41, 0x70,
        0x0E, 0x00, 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x01, 0xCA.toByte(), 0x87.toByte(),
        0xE6.toByte(), 0x6F
    )

    /** 14-byte horn blob. See [HORN_BLOB_V3] for the model-coverage caveat. */
    fun horn(): ByteArray = HORN_BLOB_V3.copyOf()

    /**
     * Legacy single-byte horn `b` (0x62) for `model < 3` firmwares (original
     * Sherman, Sherman Max on pre-2020 builds). Sherman S and newer ignore
     * this byte and only respond to [horn]; the safe default at connect-time
     * is the v3 blob, but Service Mode exposes both so a user with an older
     * Sherman can verify which one their hardware obeys.
     *
     * Spec: docs/protocols/veteran.md section 6, "Beep (firmware model < 3)".
     */
    fun hornLegacy(): ByteArray = byteArrayOf(0x62)

    /** Light on / off. ASCII strings the wheel matches verbatim. */
    fun setLight(on: Boolean): ByteArray =
        if (on) "SetLightON".toByteArray(Charsets.US_ASCII)
        else "SetLightOFF".toByteArray(Charsets.US_ASCII)

    /**
     * Pedal stiffness: hard / medium / soft. Veteran does not expose ride
     * mode (rookie / intermediate / strict) over BLE; only this 3-step
     * pedals knob. The wheel echoes the new mode at offset 30 of the next
     * telemetry frame, so callers can confirm the write took.
     */
    fun setPedalsHard(): ByteArray = "SETh".toByteArray(Charsets.US_ASCII)
    fun setPedalsMedium(): ByteArray = "SETm".toByteArray(Charsets.US_ASCII)
    fun setPedalsSoft(): ByteArray = "SETs".toByteArray(Charsets.US_ASCII)

    /**
     * Reset trip meter. Wheel zeroes the trip distance at offset 8..11 of
     * the next telemetry frame; total distance at 12..15 is unaffected.
     */
    fun resetTrip(): ByteArray = "CLEARMETER".toByteArray(Charsets.US_ASCII)
}
