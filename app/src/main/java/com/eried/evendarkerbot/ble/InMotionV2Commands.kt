package com.eried.evendarkerbot.ble

import com.eried.evendarkerbot.ble.InMotionV2Protocol.Command
import com.eried.evendarkerbot.ble.InMotionV2Protocol.ControlSubCmd
import com.eried.evendarkerbot.ble.InMotionV2Protocol.Flags
import com.eried.evendarkerbot.util.ByteUtils

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
