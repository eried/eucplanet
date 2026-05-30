package com.eried.eucplanet.ble

/**
 * Outbound command builders for the Veteran (LeaperKim) BLE protocol.
 * Commands are mostly short ASCII writes to the same 0xFFE1 characteristic
 * the wheel notifies on. The horn is the only non-ASCII command: a 14-byte
 * binary blob whose internal structure is partially understood (see spec).
 *
 * Spec: docs/protocols/veteran.md section 6.
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android, GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
object VeteranCommands {

    /**
     * Horn for `model >= 3` (Sherman S, Patton, Lynx, Abrams, Oryx, Nosfet).
     * The blob is byte-for-byte what the wheel expects; bytes 4..13 are not
     * publicly understood (possibly a session tag or feature-negotiation
     * stub) but replay works in practice. Sherman / pre-2020 firmwares
     * accept the legacy single-byte `b` instead, not exposed here because
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

    /**
     * Set the wheel's enforced tilt-back speed in km/h. Frame format
     * decoded from a captured LeaperKim-app session against a Lynx S
     * (mVer 9, May 2026): 17-byte `LdAp` frame with 8-byte payload
     * `01 02 80 80 80 80 80 [VAL]` followed by a big-endian CRC32 over
     * magic + length + payload. The value is an unsigned 8-bit km/h,
     * matched 1:1 in the video (slider showed 21 km/h ↔ wire byte 0x15,
     * 39 km/h ↔ 0x27). Clamped to 1..99 because the byte field can't
     * carry 100+, the wheel rejects 0, and over-the-firmware-limit
     * values risk an unsafe tilt-back gap with the alarm.
     */
    fun setTiltbackSpeed(kmh: Int): ByteArray =
        buildLeaperKimSpeedFrame(magic = LDAP, subOp = SUBOP_TILTBACK, kmh = kmh)

    /**
     * Set the wheel's enforced alarm speed in km/h. Frame format
     * decoded the same way as [setTiltbackSpeed]: 17-byte `LkAp` frame
     * with 8-byte payload `01 80 80 80 80 80 80 [VAL]`. Verified by
     * matching wire byte 0x14 to the Alarm-speed slider reading 20 km/h.
     */
    fun setAlarmSpeed(kmh: Int): ByteArray =
        buildLeaperKimSpeedFrame(magic = LKAP, subOp = SUBOP_ALARM, kmh = kmh)

    // ---- Other decoded LeaperKim settings (no UI binding yet) ----
    //
    // These three are byte-perfectly decoded from the same captured session as
    // setTiltbackSpeed / setAlarmSpeed, with each wire byte matched against the
    // slider value displayed in a screen-recording frame at the exact same
    // wall-clock moment (e.g. wire `0xDC` → on-screen "-3.6°"). They are not
    // wired to any UI today because EUC Planet doesn't surface those knobs,
    // but the builders live here so the protocol knowledge survives in code
    // for whoever adds the Settings screen. See docs/protocols/veteran.md
    // section 6.2 for the full frame format.
    //
    // `internal` (not private) so they're discoverable from elsewhere in the
    // ble package when we wire UI; kept off the public WheelAdapter surface
    // so they don't accidentally fire on other wheel families.

    /**
     * Set the "angle adjustment" (pedal-zero tilt offset) in tenths of a
     * degree. Signed i8: `0xDC` = -36 = -3.6° (confirmed against the slider).
     * Range observed in the LeaperKim app: roughly -10° to +10°.
     */
    internal fun setAngleAdjustmentDeci(tenthsOfDegree: Int): ByteArray {
        val clamped = tenthsOfDegree.coerceIn(-128, 127)
        return buildVendorFrame(
            magic = LKAP, totalLen = 16,
            payloadHead = byteArrayOf(0x01, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            valueByte = clamped.toByte()
        )
    }

    /** Ride-mode scalar 0..100 (LeaperKim app slider labels match raw value). */
    internal fun setRideMode(scalar: Int): ByteArray {
        val clamped = scalar.coerceIn(0, 100)
        return buildVendorFrame(
            magic = LDAP, totalLen = 15,
            payloadHead = byteArrayOf(0x01, 0x02, 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            valueByte = clamped.toByte()
        )
    }

    /** PWM percentage (observed range covers 50..70 in the captured session). */
    internal fun setPwmPercent(percent: Int): ByteArray {
        val clamped = percent.coerceIn(0, 100)
        return buildVendorFrame(
            magic = LDAP, totalLen = 18,
            payloadHead = byteArrayOf(
                0x01, 0x02,
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()
            ),
            valueByte = clamped.toByte()
        )
    }

    // 17-byte builder shared by tiltback (LdAp) and alarm (LkAp) speed writes.
    // Both differ only in (a) the magic prefix and (b) byte 1 of the payload;
    // bytes 2..6 are always 0x80 padding and byte 7 is the km/h value.
    private fun buildLeaperKimSpeedFrame(magic: ByteArray, subOp: Byte, kmh: Int): ByteArray {
        val clamped = kmh.coerceIn(1, 99)
        return buildVendorFrame(
            magic = magic, totalLen = 17,
            payloadHead = byteArrayOf(
                0x01, subOp,
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()
            ),
            valueByte = clamped.toByte()
        )
    }

    // Shared frame assembler for every LkAp / LdAp settings write. Layout:
    //   [magic 4] [length 1] [payloadHead N] [valueByte 1] [CRC32-BE 4]
    // totalLen MUST equal 4 + 1 + payloadHead.size + 1 + 4 = payloadHead.size + 10.
    // CRC32 covers magic + length + payload (everything before the trailer).
    private fun buildVendorFrame(
        magic: ByteArray,
        totalLen: Int,
        payloadHead: ByteArray,
        valueByte: Byte
    ): ByteArray {
        val out = ByteArray(totalLen)
        magic.copyInto(out, 0)
        out[4] = totalLen.toByte()
        payloadHead.copyInto(out, 5)
        out[5 + payloadHead.size] = valueByte
        val crcEnd = totalLen - 4
        val crc = java.util.zip.CRC32().apply { update(out, 0, crcEnd) }.value.toInt()
        out[crcEnd]     = ((crc ushr 24) and 0xFF).toByte()
        out[crcEnd + 1] = ((crc ushr 16) and 0xFF).toByte()
        out[crcEnd + 2] = ((crc ushr 8) and 0xFF).toByte()
        out[crcEnd + 3] = (crc and 0xFF).toByte()
        return out
    }

    private val LKAP = byteArrayOf(0x4C, 0x6B, 0x41, 0x70)  // "LkAp"
    private val LDAP = byteArrayOf(0x4C, 0x64, 0x41, 0x70)  // "LdAp"
    private const val SUBOP_TILTBACK: Byte = 0x02
    private const val SUBOP_ALARM: Byte = 0x80.toByte()
}
