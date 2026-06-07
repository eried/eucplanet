package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelSettings
import com.eried.eucplanet.util.ByteUtils
import com.eried.eucplanet.util.ByteUtils.parseTemperature
import kotlin.math.roundToInt

/**
 * Telemetry and settings parser for InMotion V12 HS / HT / Pro.
 *
 * V12's RealTimeInfo packet uses tighter byte packing than V14: speed/torque/
 * pwm sit at lower offsets, the dual-battery split is collapsed into a single
 * battery-level field, and pcMode/light state moves earlier in the packet.
 * Keep this in a separate file from [InMotionV2Parser] so the V14 layout is
 * not disturbed.
 *
 * Offsets and scaling are derived from public BLE-protocol research. Fresh
 * Kotlin implementation; no third-party source code reproduced.
 */
object InMotionV2ParserV12 {

    /**
     * V12 RealTimeInfo (cmd 0x04) layout. Every multi-byte value is little-endian.
     *
     *   byte 0..1   voltage          uint16  (×0.01 V)
     *   byte 2..3   current          int16   (×0.01 A, signed)
     *   byte 4..5   speed            int16   (×0.01 km/h, signed)
     *   byte 6..7   torque           int16   (×0.01 N·m, signed)
     *   byte 8..9   pwm              int16   (%×100, signed)
     *   byte 10..11 batPower         int16   (W ×100, signed)
     *   byte 12..13 motPower         int16   (W, signed)
     *   byte 14..15 reserved (always 0)
     *   byte 16..17 pitchAngle       int16   (×0.01°, signed)
     *   byte 18..19 pitchAimAngle    int16   (×0.01°, signed), unused
     *   byte 20..21 rollAngle        int16   (×0.01°, signed)
     *   byte 22..23 mileage          uint16  (units of 0.01 km)
     *   byte 24..25 batLevel         uint16  (units of 0.01 %)
     *   byte 26..27 remainMileage    uint16, unused
     *   byte 28..29 reserved (always 18000)
     *   byte 30..31 dynSpeedLimit    uint16  (×0.01 km/h)
     *   byte 32..33 dynCurrentLimit  uint16  (×0.01 A)
     *   byte 40..46 6 temperatures   uint8   (offset by 176, see parseTemperature)
     *   byte 54     state byte:
     *               bits 0..2  = pcMode (0=lock, 1=drive, 2=shutdown, 3=idle)
     *               bits 3..5  = mcMode
     *               bit  6     = motor active
     *               bit  7     = charging
     *   byte 55     light byte:
     *               bit 0 = low beam
     *               bit 1 = high beam
     *               bit 2 = lifted
     */
    fun parseTelemetry(data: ByteArray): WheelData? {
        if (data.size < 56) return null

        val voltage = ByteUtils.getUint16LE(data, 0) / 100f
        val current = ByteUtils.getInt16LE(data, 2) / 100f
        val speed = ByteUtils.getInt16LE(data, 4) / 100f
        val torque = ByteUtils.getInt16LE(data, 6) / 100f
        val pwm = ByteUtils.getInt16LE(data, 8) / 100f
        val batteryPower = ByteUtils.getInt16LE(data, 10) * 100
        val motorPower = ByteUtils.getInt16LE(data, 12)
        val pitchAngle = ByteUtils.getInt16LE(data, 16) / 100f
        val rollAngle = ByteUtils.getInt16LE(data, 20) / 100f
        // Mileage stored in units of 100m → convert to km
        val tripDistanceKm = ByteUtils.getUint16LE(data, 22) / 100f
        // V12 reports a single battery level (0..10000 → 0..100%)
        val batteryLevel = ByteUtils.getUint16LE(data, 24) / 100f
        val dynamicSpeedLimit = ByteUtils.getUint16LE(data, 30) / 100f
        val dynamicCurrentLimit = ByteUtils.getUint16LE(data, 32) / 100f

        // Temperatures: MOS, MOT, BAT (always 0), BOARD, CPU, IMU, LAMP (always 0).
        // Skip the always-zero ones so maxTemperature isn't dragged toward -176°C.
        val temps = mutableListOf<Float>()
        for (offset in intArrayOf(40, 41, 43, 44, 45)) {
            if (offset < data.size) temps.add(parseTemperature(data[offset]))
        }

        val stateByte = data[54].toInt() and 0xFF
        val pcMode = stateByte and 0x07
        // Bit 7 of the state byte is the charging flag (same layout as V14).
        val isCharging = (stateByte and 0x80) != 0

        val lightByte = data[55].toInt() and 0xFF
        // Treat any beam (low or high) as "light on" for the dashboard indicator
        val lightOn = (lightByte and 0x03) != 0

        // V12's battery field is a real percentage; round it so 96.7 reads
        // 97 like the wheel screen, not 96.
        val batteryPercent = batteryLevel.roundToInt().coerceIn(0, 100)

        return WheelData(
            speed = speed,
            voltage = voltage,
            current = current,
            batteryPercent = batteryPercent,
            // V12 reports a single battery; mirror to both halves so the dashboard
            // shows one consistent number rather than two empty halves.
            battery1Percent = batteryLevel,
            battery2Percent = batteryLevel,
            pwm = pwm,
            torque = torque,
            temperatures = temps,
            maxTemperature = temps.maxOrNull() ?: 0f,
            tripDistance = tripDistanceKm,
            pitchAngle = pitchAngle,
            rollAngle = rollAngle,
            batteryPower = batteryPower,
            motorPower = motorPower,
            dynamicSpeedLimit = dynamicSpeedLimit,
            dynamicCurrentLimit = dynamicCurrentLimit,
            lightOn = lightOn,
            charging = isCharging,
            pcMode = pcMode,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * V12 Settings (cmd 0x20) layout. Payload starts at byte 0 (the leading
     * sub-cmd echo at byte 0 is treated as part of the V12 layout). Fields we
     * care about for our UI are tiltback/alarm speeds; the rest is left at
     * their model defaults.
     *
     *   byte 9..10   maxSpeedLim    uint16  (×0.01 km/h)
     *   byte 11..12  alarmSpeed1    int16   (×0.01 km/h, signed)
     *   byte 13..14  alarmSpeed2    int16   (×0.01 km/h, signed), second alarm
     *   byte 15..16  pedalsAdjust   int16   (×0.1°, signed)
     *   byte 17..18  standbyDelay   uint16  (seconds)
     *   byte 19      bit 0 = classic ride mode, bit 4 = fancier mode
     *   byte 22      speaker volume
     *   byte 39      bit 0 = NOT mute, bit 6 = transport mode
     *
     * V12 has no reliable lockState byte in the public layout; WheelLog leaves
     * it commented out. We keep [WheelSettings.lockState] at 0 (unlocked) and
     * rely on the wheel's actual response to the lock command for state, not
     * settings introspection.
     */
    fun parseSettings(data: ByteArray): WheelSettings? {
        if (data.size < 42) return null

        val maxSpeed = ByteUtils.getUint16LE(data, 9) / 100f
        val alarmSpeed = ByteUtils.getInt16LE(data, 11) / 100f
        val pedalsAdjust = ByteUtils.getInt16LE(data, 15) / 10f
        val standbyDelaySec = ByteUtils.getUint16LE(data, 17)

        val classicMode = (data[19].toInt() and 0x01) != 0
        val fancierMode = ((data[19].toInt() shr 4) and 0x01) != 0

        val byte39 = data[39].toInt() and 0xFF
        // bit 0 = sound on; mute = sound off
        val mute = (byte39 and 0x01) == 0
        val transportMode = ((byte39 shr 6) and 0x01) != 0

        return WheelSettings(
            maxSpeedKmh = maxSpeed,
            alarmSpeedKmh = alarmSpeed,
            pedalAdjustment = pedalsAdjust,
            offroadMode = false,        // V12 doesn't expose this in the public layout
            fancierMode = fancierMode,
            comfortSensitivity = 0,     // present in raw at byte 20 but not surfaced today
            classicSensitivity = 0,
            standbyDelayMinutes = standbyDelaySec / 60,
            mute = mute,
            drl = false,                // V12 has no DRL
            transportMode = transportMode,
            lockState = 0
        )
    }
}
