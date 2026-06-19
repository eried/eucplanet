package com.eried.eucplanet.ble

/**
 * Outbound command builders for the Veteran (LeaperKim) BLE protocol.
 * Commands are mostly short ASCII writes to the same 0xFFE1 characteristic
 * the wheel notifies on. The horn is the only non-ASCII command: a 14-byte
 * binary blob whose internal structure is partially understood (see spec).
 *
 * Spec: docs/protocols/veteran.md section 6.
 */
object VeteranCommands {

    /**
     * Horn — first of a TWO-frame command on current LeaperKim firmware.
     *
     * A Lynx S btsnoop (mVer 9, May 2026) shows the official app sounds the
     * horn by writing this `LkAp` frame immediately followed by the
     * [hornCompanion] `LdAp` frame. Sending the `LkAp` half alone is
     * accepted (the CRC32 is valid) but does NOT beep -- confirmed in the
     * field where the blob reached the wheel four times with no sound.
     * Always send [horn] then [hornCompanion].
     *
     * Wire: `4c 6b 41 70 0e 00 80 80 80 01 ca 87 e6 6f` -- a 14-byte
     * vendor frame (payload `00 80 80 80 01`, big-endian CRC32 trailer).
     * Pre-2020 Sherman (model < 3) instead take the single byte [hornLegacy].
     *
     * Spec: docs/protocols/veteran.md section 6, "Beep".
     */
    fun horn(): ByteArray = buildVendorFrame(
        magic = LKAP, totalLen = 14,
        payloadHead = byteArrayOf(0x00, 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
        valueByte = 0x01
    )

    /**
     * Second frame of the horn (`LdAp`), required by current Lynx-class firmware;
     * see [horn] for why the `LkAp` frame alone doesn't beep. Sent right after
     * [horn] as a separate BLE write (the official app splits the pair the same
     * way at the 20-byte ATT boundary).
     *
     * Wire: `4c 64 41 70 0e 00 00 80 80 01 f8 67 9f 85` — 14-byte vendor frame
     * (payload `00 00 80 80 01`, big-endian CRC32 trailer).
     */
    fun hornCompanion(): ByteArray = buildVendorFrame(
        magic = LDAP, totalLen = 14,
        payloadHead = byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x80.toByte()),
        valueByte = 0x01
    )

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

    /** Low-beam on / off. ASCII strings the wheel matches verbatim. */
    fun setLight(on: Boolean): ByteArray =
        if (on) "SetLightON".toByteArray(Charsets.US_ASCII)
        else "SetLightOFF".toByteArray(Charsets.US_ASCII)

    /**
     * High beam on/off — the LeaperKim binary headlight command (`LkAp` frame;
     * companion `LdAp` in [setHighBeamCompanion]). Captured from the LeaperKim
     * app on a Lynx S: a 13-byte vendor frame, payload `01 80 80 <state>`, state
     * `01`=on / `00`=off. This is a SEPARATE light from the ASCII [setLight] low
     * beam — the wheel drives the two independently. Send [setHighBeam] then
     * [setHighBeamCompanion] (the wheel ignores the `LkAp` half on its own, same
     * as the horn). See docs/protocols/veteran.md section 6.2.
     */
    fun setHighBeam(on: Boolean): ByteArray = buildVendorFrame(
        magic = LKAP, totalLen = 13,
        payloadHead = byteArrayOf(0x01, 0x80.toByte(), 0x80.toByte()),
        valueByte = if (on) 0x01 else 0x00
    )

    /** Companion `LdAp` frame for [setHighBeam]; payload `01 00 80 <state>`. */
    fun setHighBeamCompanion(on: Boolean): ByteArray = buildVendorFrame(
        magic = LDAP, totalLen = 13,
        payloadHead = byteArrayOf(0x01, 0x00, 0x80.toByte()),
        valueByte = if (on) 0x01 else 0x00
    )

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
     * Software lock / unlock. 25-byte `LdAp` vendor frame with payload
     *
     *   00 05 1a 06 <day> <hour> <minute> <second> 02 04 0c ab <state> 00 00 00
     *
     * followed by a big-endian CRC32 over magic + length + payload. `<state>`
     * is `0x01` for lock, `0x00` for unlock.
     *
     * Bytes 4..7 of the payload encode the rider's local wall-clock at the
     * moment of writing: day-of-month, hour-of-day, minute, second — each one
     * 1 byte, no encoding tricks. Verified against a Lynx S btsnoop where
     * twenty paired lock/unlock writes all match within a second of the
     * captured packet's HCI timestamp, including a minute rollover from
     * 10:59 → 11:00 mid-test.
     *
     * The first attempt at this protocol treated byte 7 as a session-
     * monotonic counter, hardcoded bytes 4..6 to the wall-clock at the
     * moment of the original capture (the 17th at 15:10), and bumped the
     * counter on each write. Every frame we sent was a frozen "17 days ago
     * at 15:10:09" timestamp — the wheel rejected them and toggles silently
     * no-op'd. Using the real wall clock each write fixes it.
     *
     * Older Sherman / Sherman Max wheels (model < 3) haven't been captured
     * doing this; the wheel will silently ignore a frame it doesn't
     * recognise, so wiring this on the whole family is safe.
     */
    fun setLock(locked: Boolean): ByteArray = setLock(locked, java.util.Calendar.getInstance())

    /** Testable overload: pass a fixed [Calendar] to verify the wire bytes. */
    internal fun setLock(locked: Boolean, now: java.util.Calendar): ByteArray {
        val state: Byte = if (locked) 0x01 else 0x00
        val day = now.get(java.util.Calendar.DAY_OF_MONTH).toByte()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY).toByte()
        val minute = now.get(java.util.Calendar.MINUTE).toByte()
        val second = now.get(java.util.Calendar.SECOND).toByte()
        return buildVendorFrame(
            magic = LDAP, totalLen = 25,
            payloadHead = byteArrayOf(
                0x00, 0x05, 0x1A, 0x06,
                day, hour, minute, second,
                0x02, 0x04, 0x0C, 0xAB.toByte(),
                state, 0x00, 0x00,
            ),
            valueByte = 0x00,
        )
    }

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
