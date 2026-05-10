package com.eried.eucplanet.ble

import com.eried.eucplanet.ble.InMotionV2Protocol.Command
import com.eried.eucplanet.ble.InMotionV2Protocol.ControlSubCmd
import com.eried.eucplanet.ble.InMotionV2Protocol.Flags
import com.eried.eucplanet.util.ByteUtils

/**
 * Command builders for InMotion V2 protocol (V11/V12/V13/V14).
 * Each method returns a complete BLE packet ready to write.
 */
object InMotionV2Commands {

    // --- Authentication (required before lock/unlock) ---

    /**
     * Request the encrypted password key from the wheel.
     * The wheel responds with 16 bytes of AES-encrypted password data.
     *
     * Packet: AA AA 13 04 02 00 00 02 17
     * Format matches official InMotion app: flags=0x13, routing=[02,00], cmd=0x00, data=[02]
     * (Our buildPacket treats the first routing byte 0x02 as the "command" position.)
     */
    fun requestAuthKey(): ByteArray =
        InMotionV2Protocol.buildPacket(0x13, 0x02, byteArrayOf(0x00, 0x00, 0x02))

    /**
     * Verify password by echoing the encrypted key back to the wheel.
     * The wheel responds with [01] = success.
     *
     * Packet: AA AA 13 14 02 00 00 82 [16 bytes] checksum
     */
    fun verifyAuth(encryptedKey: ByteArray): ByteArray {
        val data = ByteArray(3 + encryptedKey.size)
        data[0] = 0x00
        data[1] = 0x00
        data[2] = 0x82.toByte()
        encryptedKey.copyInto(data, 3)
        return InMotionV2Protocol.buildPacket(0x13, 0x02, data)
    }

    // --- Initialization sequence ---

    fun getCarType(): ByteArray =
        InMotionV2Protocol.buildPacket(Flags.INITIAL, Command.MAIN_INFO, byteArrayOf(0x01))

    fun getSerialNumber(): ByteArray =
        InMotionV2Protocol.buildPacket(Flags.INITIAL, Command.MAIN_INFO, byteArrayOf(0x02))

    fun getVersions(): ByteArray =
        InMotionV2Protocol.buildPacket(Flags.INITIAL, Command.MAIN_INFO, byteArrayOf(0x06))

    fun getCurrentSettings(): ByteArray =
        InMotionV2Protocol.buildPacket(Flags.DEFAULT, Command.SETTINGS, byteArrayOf(0x20))

    fun getUselessData(): ByteArray =
        InMotionV2Protocol.buildPacket(Flags.DEFAULT, Command.SOMETHING1, byteArrayOf(0x00, 0x01))

    fun getStatistics(): ByteArray =
        InMotionV2Protocol.buildPacket(Flags.DEFAULT, Command.TOTAL_STATS, byteArrayOf())

    // --- Keep-alive / telemetry request ---

    fun getRealTimeData(): ByteArray =
        InMotionV2Protocol.buildPacket(Flags.DEFAULT, Command.REAL_TIME_INFO, byteArrayOf())

    // --- InMotion P6 (extended-routing-only variant) ---
    //
    // The P6 ignores the legacy `02 [cmd]` queries (carType returns all zeros in
    // captures) and only responds to extended-routing queries `02 21 [sub]`.
    // Confirmed sub-commands seen on a real P6 (firmware A14219B): 0x06 info
    // bundle (serial + version), 0x07 realtime telemetry, 0x04 total stats.
    // We send only the info + realtime queries — settings (sub 0x20) and ride
    // history (sub 0x10/0x11) have a TLV-style layout we haven't reverse-
    // engineered yet, so polling them just produces unparsed bytes.

    fun getP6Info(): ByteArray =
        InMotionV2Protocol.buildExtendedPacket(0x06, byteArrayOf())

    fun getP6RealTimeData(): ByteArray =
        InMotionV2Protocol.buildExtendedPacket(0x07, byteArrayOf())

    /**
     * Read settings page A (`02 21 20 [20]`). Comes back as a 51-byte body
     * with current tiltback at offset 13-14 (uint16 LE / 100, km/h).
     */
    fun getP6Settings(): ByteArray =
        InMotionV2Protocol.buildExtendedPacket(0x20, byteArrayOf(0x20))

    /**
     * Set the P6 tiltback / max speed.
     *
     * P6 takes a 2-byte uint16 LE value in 0.01 km/h units, NOT the V14's
     * 4-byte (tilt + alarm) packet. `60 21 [v_lo v_hi]` alone is sufficient
     * and persists across reboots — multiple mid-drag writes without any
     * follow-up commit are honoured by the wheel and stay put. Pair it with
     * [setP6AlarmSpeed] only if you also want to change the alarm threshold.
     */
    fun setP6MaxSpeed(tiltbackKmh: Float): ByteArray {
        val v = ByteUtils.putUint16LE((tiltbackKmh * 100).toInt())
        return InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(ControlSubCmd.SET_MAX_SPEED, v[0], v[1])
        )
    }

    /**
     * P6 light: `60 50 [on/off, on/off]`. The second byte mirrors the first
     * — V14 uses a 1-byte arg and is silently ignored by the P6, which is
     * why the watch / phone toggle didn't take on Gio's wheel.
     */
    fun setP6Light(on: Boolean): ByteArray {
        val v = if (on) 0x01.toByte() else 0x00.toByte()
        return InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(ControlSubCmd.SET_LIGHT, v, v)
        )
    }

    /**
     * P6 horn: `60 51 [18 01]`. V14 sends `[02 64]` (sound id + volume) and
     * the P6 ignores it. Args here are taken verbatim from the InMotion app
     * capture and produce the standard P6 chirp.
     */
    fun hornP6(): ByteArray =
        InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(ControlSubCmd.PLAY_SOUND, 0x18, 0x01)
        )

    /**
     * P6 Auto Headlight toggle (the "Auto Headlight" switch under General
     * Settings → Lighting). Wire format: `aa aa 16 05 02 21 60 2f [v] [chk]`,
     * single-byte payload (1 = ON, 0 = OFF). Verified against the FINAL P6
     * capture — five toggles by the user produced exactly this frame each
     * time, with `2f 01 7e` for ON and `2f 00 7f` for OFF.
     *
     * On a P6, manual Light (`60 50`) is independent of Auto Headlight: when
     * Auto Headlight is on, the wheel decides based on ambient light; when
     * it is off, the user controls the headlight directly via `60 50`.
     */
    fun setP6AutoHeadlight(on: Boolean): ByteArray =
        InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(0x2F, if (on) 0x01 else 0x00)
        )

    /**
     * Set the P6 **Speed Limit Alarm** (the threshold above which the wheel
     * beeps). Goes through `60 3e [v_lo v_hi 00 00]` — same opcode the
     * InMotion app uses for any commit-to-flash scalar setting. Confirmed
     * against `docs/P6_CAPTURE_LABELS.md`: 13679 = 136.79 km/h = 85 mph
     * matched the on-screen 85 mph alarm value during the labelled capture.
     */
    fun setP6AlarmSpeed(alarmKmh: Float): ByteArray {
        val v = ByteUtils.putUint16LE((alarmKmh * 100).toInt())
        return InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(0x3E, v[0], v[1], 0x00, 0x00)
        )
    }

    /**
     * Set all three P6 **PWM thresholds** at once via `60 4c`:
     *   - `tiltbackPct`: PWM Tilt-back Limit  (0-100)
     *   - `alarm1Pct`:   PWM Level 1 Alarm    (0-100)
     *   - `alarm2Pct`:   PWM Level 2 Alarm    (0-100)
     *
     * Wire format: 3 × uint16 LE in 0.01% units. The InMotion app sends all
     * three in one packet whenever any slider changes, so we mirror that.
     */
    fun setP6PwmThresholds(tiltbackPct: Float, alarm1Pct: Float, alarm2Pct: Float): ByteArray {
        val t = ByteUtils.putUint16LE((tiltbackPct * 100).toInt())
        val a1 = ByteUtils.putUint16LE((alarm1Pct * 100).toInt())
        val a2 = ByteUtils.putUint16LE((alarm2Pct * 100).toInt())
        return InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(0x4C, t[0], t[1], a1[0], a1[1], a2[0], a2[1])
        )
    }

    /**
     * Toggle the P6 **Speed Clamp at 25 km/h** safety. Maps to the InMotion
     * app's "Speed Clamp at 25km/h" switch in General Settings.
     */
    fun setP6SpeedClamp25(on: Boolean): ByteArray =
        InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(0x24, if (on) 0x01 else 0x00)
        )

    /**
     * Set the P6 **Pedal Hardness** slider. The InMotion app sends two bytes
     * `[live, committed]` while dragging — we send the same value in both
     * since we're emitting a single atomic set rather than a drag stream.
     */
    fun setP6PedalHardness(percent: Int): ByteArray {
        val v = percent.coerceIn(0, 100).toByte()
        return InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(0x25, v, v)
        )
    }

    // --- Control commands ---

    /**
     * Set max speed (tiltback) and beep alarm speed for V14.
     * Both values in km/h. Encoded as uint16 LE * 100.
     */
    fun setMaxSpeedV14(tiltbackKmh: Float, beepAlarmKmh: Float): ByteArray {
        val tiltback = ByteUtils.putUint16LE((tiltbackKmh * 100).toInt())
        val beep = ByteUtils.putUint16LE((beepAlarmKmh * 100).toInt())
        return InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(ControlSubCmd.SET_MAX_SPEED, tiltback[0], tiltback[1], beep[0], beep[1])
        )
    }

    /**
     * Toggle DRL (daytime running light).
     */
    fun setDRL(on: Boolean): ByteArray =
        InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(ControlSubCmd.SET_DRL, if (on) 0x01 else 0x00)
        )

    /**
     * Lock or unlock the wheel.
     *
     * Uses the EXTENDED packet format (flags=0x16 with routing bytes 0x02 0x21)
     * which matches the official InMotion app. The DEFAULT format (flags=0x14)
     * only works on V11 and older; V12/V13/V14 require the extended format
     * for security-sensitive commands like lock/unlock.
     *
     * Lock packet:   AA AA 16 05 02 21 60 31 01 60
     * Unlock packet: AA AA 16 05 02 21 60 31 00 61
     */
    fun setLock(locked: Boolean): ByteArray =
        InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(ControlSubCmd.SET_LOCK, if (locked) 0x01 else 0x00)
        )

    /**
     * Turn headlight on/off.
     */
    fun setLight(on: Boolean): ByteArray =
        InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(ControlSubCmd.SET_LIGHT, if (on) 0x01 else 0x00)
        )

    /**
     * Sound the horn/beep.
     * beepType: 0x02 = horn sound, 0x64 = volume
     */
    fun horn(): ByteArray =
        InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(ControlSubCmd.PLAY_SOUND, 0x02, 0x64)
        )

    /**
     * Set speaker volume (0-100).
     */
    fun setVolume(volume: Int): ByteArray =
        InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(ControlSubCmd.SET_VOLUME, volume.coerceIn(0, 100).toByte())
        )
}
