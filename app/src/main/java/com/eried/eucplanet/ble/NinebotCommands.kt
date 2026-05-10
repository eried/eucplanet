package com.eried.eucplanet.ble

import com.eried.eucplanet.util.ByteUtils

/**
 * Outbound command builders for the Ninebot Z protocol.
 *
 * Every Z frame is `5A A5 <len> <src=0x3E> <dst> <cmd> <param> <data...> <crc16 LE>`,
 * total `len + 9` bytes (spec docs/protocols/ninebot.md section 3). The CRC is
 * computed over plaintext bytes from the length field through the last data
 * byte; encryption (XOR keystream) is applied later by the adapter once the
 * GetKey handshake finishes. See [NinebotZCrypto].
 *
 * Legacy protocol writes are NOT implemented here: the legacy stack does not
 * expose lock, alarms, light, volume, or LED through any documented parameter
 * (spec section 16). Legacy support is read-only; the adapter returns null for
 * every control method.
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik / Palachzzz and contributors,
 * https://github.com/Wheellog/wheellog.android — GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
object NinebotCommands {

    private const val MAGIC_0: Byte = 0x5A
    private const val MAGIC_1: Byte = 0xA5.toByte()

    /** Phone source address — spec section 4. */
    const val ADDR_APP: Int = 0x3E
    /** Main MCU. */
    const val ADDR_CONTROLLER: Int = 0x14
    /** Encryption-key oracle, replies once during handshake. */
    const val ADDR_KEY_GENERATOR: Int = 0x16

    object Cmd {
        const val READ: Int = 0x01
        const val WRITE: Int = 0x03
        const val GET: Int = 0x04
        const val GET_KEY: Int = 0x5B
    }

    object Param {
        const val GET_KEY: Int = 0x00
        const val SERIAL_NUMBER: Int = 0x10
        const val FIRMWARE: Int = 0x1A
        const val BLE_VERSION: Int = 0x68
        const val LOCK_MODE: Int = 0x70
        const val LIMITED_MODE: Int = 0x72
        const val LIMIT_MODE_SPEED: Int = 0x74
        const val ALARMS: Int = 0x7C
        const val ALARM1_SPEED: Int = 0x7D
        const val ALARM2_SPEED: Int = 0x7E
        const val ALARM3_SPEED: Int = 0x7F
        const val LIVE_DATA: Int = 0xB0
        const val LED_MODE: Int = 0xC6
        const val PEDAL_SENSITIVITY: Int = 0xD2
        const val DRIVE_FLAGS: Int = 0xD3
        const val SPEAKER_VOLUME: Int = 0xF5
    }

    /**
     * Assemble a plaintext Z frame around a payload. Caller hands in the
     * `cmd`, `param`, and `data` bytes; the helper writes the magic, length,
     * addresses, and CRC. Encryption is applied *after* this builder by
     * [NinebotZCrypto.applyInPlace] — keeping plaintext+CRC computation in
     * one place means the CRC is always over plaintext, which is what the
     * receiver expects (spec section 6.2 final paragraph).
     *
     * On-wire layout:
     *   `5A A5 <len> <src> <dst> <cmd> <param> <data...> <crc_lo> <crc_hi>`
     */
    fun frame(src: Int, dst: Int, cmd: Int, param: Int, data: ByteArray): ByteArray {
        val len = data.size
        val out = ByteArray(len + 9)
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        out[2] = (len and 0xFF).toByte()
        out[3] = (src and 0xFF).toByte()
        out[4] = (dst and 0xFF).toByte()
        out[5] = (cmd and 0xFF).toByte()
        out[6] = (param and 0xFF).toByte()
        System.arraycopy(data, 0, out, 7, len)

        // CRC = ones-complement of the 16-bit running sum, length field
        // through the last data byte (spec section 3, "Checksum").
        var sum = 0
        for (i in 2 until 7 + len) sum = (sum + (out[i].toInt() and 0xFF)) and 0xFFFF
        val crc = sum xor 0xFFFF
        out[7 + len] = (crc and 0xFF).toByte()
        out[8 + len] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    /**
     * GetKey: phone -> KeyGenerator (0x16), cmd 0x5B, param 0x00, no data.
     * Sent in the clear — no key is installed yet — and the wheel's reply
     * arrives in the clear too (spec section 6.1). The reply's data field
     * is the 16-byte session key.
     */
    fun getKey(): ByteArray =
        frame(ADDR_APP, ADDR_KEY_GENERATOR, Cmd.GET_KEY, Param.GET_KEY, ByteArray(0))

    /** Read controller BleVersion (state 0 of the connect dance). */
    fun getBleVersion(): ByteArray =
        frame(ADDR_APP, ADDR_CONTROLLER, Cmd.READ, Param.BLE_VERSION, byteArrayOf(0x02))

    /** Read controller serial number. */
    fun getSerialNumber(): ByteArray =
        frame(ADDR_APP, ADDR_CONTROLLER, Cmd.READ, Param.SERIAL_NUMBER, byteArrayOf(0x0E))

    /** Read controller firmware version. */
    fun getFirmware(): ByteArray =
        frame(ADDR_APP, ADDR_CONTROLLER, Cmd.READ, Param.FIRMWARE, byteArrayOf(0x02))

    /**
     * Get live telemetry (param 0xB0). Z polls; the wheel does not push.
     * Data byte `0x20` requests "all live fields" and produces the 28-byte
     * payload the parser expects. The official app uses the same byte.
     */
    fun getLiveData(): ByteArray =
        frame(ADDR_APP, ADDR_CONTROLLER, Cmd.GET, Param.LIVE_DATA, byteArrayOf(0x20))

    /**
     * Bundle of read requests used for the periodic settings refresh. We
     * could send each separately but stuffing them on the same poll tick
     * keeps the round-trip count down. Caller fans these out one-per-tick.
     */
    fun readLockState(): ByteArray =
        frame(ADDR_APP, ADDR_CONTROLLER, Cmd.READ, Param.LOCK_MODE, byteArrayOf(0x02))

    fun readSpeedLimit(): ByteArray =
        frame(ADDR_APP, ADDR_CONTROLLER, Cmd.READ, Param.LIMIT_MODE_SPEED, byteArrayOf(0x02))

    fun readAlarms(): ByteArray =
        frame(ADDR_APP, ADDR_CONTROLLER, Cmd.READ, Param.ALARMS, byteArrayOf(0x02))

    fun readVolume(): ByteArray =
        frame(ADDR_APP, ADDR_CONTROLLER, Cmd.READ, Param.SPEAKER_VOLUME, byteArrayOf(0x02))

    fun readDriveFlags(): ByteArray =
        frame(ADDR_APP, ADDR_CONTROLLER, Cmd.READ, Param.DRIVE_FLAGS, byteArrayOf(0x02))

    // ---- Writes -------------------------------------------------------------

    /**
     * Lock the wheel. Spec section 10: write 0x70 with `{0x01, 0x00}` to
     * lock, `{0x00, 0x00}` to unlock. The wheel itself does not enforce a
     * PIN — the encrypted handshake is the only barrier — but the official
     * app gates this UX-side and we mirror that in our app layer.
     */
    fun setLock(locked: Boolean): ByteArray = frame(
        ADDR_APP, ADDR_CONTROLLER, Cmd.WRITE, Param.LOCK_MODE,
        byteArrayOf(if (locked) 0x01 else 0x00, 0x00)
    )

    /** Toggle the speed-limit feature on/off (param 0x72). */
    fun setLimitedMode(enabled: Boolean): ByteArray = frame(
        ADDR_APP, ADDR_CONTROLLER, Cmd.WRITE, Param.LIMITED_MODE,
        byteArrayOf(if (enabled) 0x01 else 0x00, 0x00)
    )

    /** Set the speed-limit value. Encoded as `km/h * 100`, u16 LE. */
    fun setSpeedLimit(kmh: Float): ByteArray {
        val raw = (kmh.coerceAtLeast(0f) * 100f).toInt().coerceIn(0, 0xFFFF)
        return frame(
            ADDR_APP, ADDR_CONTROLLER, Cmd.WRITE, Param.LIMIT_MODE_SPEED,
            ByteUtils.putUint16LE(raw)
        )
    }

    /**
     * Arm/disarm the three alarm slots. Bits 0..2 of the bitfield enable
     * alarms 1..3 respectively. The high byte is reserved (0).
     */
    fun setAlarmsArmed(slot1: Boolean, slot2: Boolean, slot3: Boolean): ByteArray {
        val bits = (if (slot1) 0x01 else 0) or
                (if (slot2) 0x02 else 0) or
                (if (slot3) 0x04 else 0)
        return frame(
            ADDR_APP, ADDR_CONTROLLER, Cmd.WRITE, Param.ALARMS,
            byteArrayOf(bits.toByte(), 0x00)
        )
    }

    private fun setAlarmSpeedRaw(param: Int, kmh: Float): ByteArray {
        val raw = (kmh.coerceAtLeast(0f) * 100f).toInt().coerceIn(0, 0xFFFF)
        return frame(ADDR_APP, ADDR_CONTROLLER, Cmd.WRITE, param, ByteUtils.putUint16LE(raw))
    }

    /** Alarm 1 speed, km/h. u16 LE, value = `km/h * 100`. */
    fun setAlarm1Speed(kmh: Float): ByteArray = setAlarmSpeedRaw(Param.ALARM1_SPEED, kmh)
    fun setAlarm2Speed(kmh: Float): ByteArray = setAlarmSpeedRaw(Param.ALARM2_SPEED, kmh)
    fun setAlarm3Speed(kmh: Float): ByteArray = setAlarmSpeedRaw(Param.ALARM3_SPEED, kmh)

    /** LED mode 0..7. Mode 0 turns the LED off; mode 1..7 are pattern presets. */
    fun setLedMode(mode: Int): ByteArray = frame(
        ADDR_APP, ADDR_CONTROLLER, Cmd.WRITE, Param.LED_MODE,
        byteArrayOf(mode.coerceIn(0, 7).toByte())
    )

    /**
     * Drive flags bitfield (0xD3). Bit 0 is DRL (daytime running light).
     * Spec section 7 lists bits 1..4 for tail light, aux light, strain
     * gauge / pedal pressure, brake assist; bit 3 has a "?" in WheelLog's
     * reference and we have not validated it on real hardware — see
     * spec open question 3.
     */
    fun setDriveFlags(flags: Int): ByteArray = frame(
        ADDR_APP, ADDR_CONTROLLER, Cmd.WRITE, Param.DRIVE_FLAGS,
        byteArrayOf(flags.toByte(), 0x00)
    )

    /**
     * Speaker volume. Per spec the encoded value is `volume << 3`, so 0..7
     * map to bytes 0x00 / 0x08 / ... / 0x38. Caller passes the 0..7 step.
     */
    fun setVolume(step: Int): ByteArray {
        val encoded = (step.coerceIn(0, 7) shl 3) and 0xFF
        return frame(
            ADDR_APP, ADDR_CONTROLLER, Cmd.WRITE, Param.SPEAKER_VOLUME,
            byteArrayOf(encoded.toByte(), 0x00)
        )
    }

    /**
     * Pedal sensitivity, u16 LE. Values are firmware-specific; the official
     * app exposes a 0..100 slider but the underlying scale isn't in the
     * spec, so callers should pass the raw value they want written.
     */
    fun setPedalSensitivity(raw: Int): ByteArray = frame(
        ADDR_APP, ADDR_CONTROLLER, Cmd.WRITE, Param.PEDAL_SENSITIVITY,
        ByteUtils.putUint16LE(raw.coerceIn(0, 0xFFFF))
    )

    // ---- Legacy ------------------------------------------------------------

    // Legacy Ninebot exposes no documented settings writes through the BLE
    // stack (spec section 16). Lock, alarms, lights, volume, LED — none of
    // them have a parameter ID. Settings changes on One E / E+ / S2 / Mini /
    // Mini Pro require the official Ninebot app over a side channel we don't
    // cover. The legacy adapter therefore returns null from every control
    // method; no command builders live here for legacy.
}
