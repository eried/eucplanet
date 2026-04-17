package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelSettings
import com.eried.eucplanet.util.ByteUtils
import com.eried.eucplanet.util.ByteUtils.parseTemperature

/**
 * Parses telemetry and settings responses from InMotion V14.
 * Field offsets based on WheelLog InmotionAdapterV2 V14 parsing.
 */
object InMotionV2Parser {

    /**
     * Parse RealTimeInfo response (command 0x04) into WheelData.
     * V14 telemetry layout.
     */
    fun parseTelemetry(data: ByteArray): WheelData? {
        if (data.size < 65) return null

        val voltage = ByteUtils.getUint16LE(data, 0) / 100f
        val current = ByteUtils.getInt16LE(data, 2) / 100f
        val speed = ByteUtils.getInt16LE(data, 8) / 100f
        val torque = ByteUtils.getInt16LE(data, 12) / 100f
        val pwm = ByteUtils.getInt16LE(data, 14) / 100f
        val batteryPower = ByteUtils.getInt16LE(data, 16)
        val motorPower = ByteUtils.getInt16LE(data, 18)
        val pitchAngle = ByteUtils.getInt16LE(data, 20) / 100f
        val rollAngle = ByteUtils.getInt16LE(data, 22) / 100f
        val tripDistance = ByteUtils.getUint16LE(data, 28) * 10f // meters
        val battery1 = ByteUtils.getUint16LE(data, 34) / 100f
        val battery2 = ByteUtils.getUint16LE(data, 36) / 100f
        val dynamicSpeedLimit = ByteUtils.getUint16LE(data, 40) / 100f

        // Temperatures at offsets 58-63 (6 sensors: board, motor, IMU, etc.)
        // Byte 64+ is NOT temperature (varies wildly in captures)
        val temps = mutableListOf<Float>()
        for (i in 58..63) {
            if (i < data.size) {
                temps.add(parseTemperature(data[i]))
            }
        }

        // Dynamic current limit at offset 50-51
        val dynamicCurrentLimit = if (data.size > 51) {
            ByteUtils.getUint16LE(data, 50) / 100f
        } else 0f

        // PC Mode at offset 74: encodes wheel state
        val pcMode = if (data.size > 74) data[74].toInt() and 0x07 else 0

        // Light state at offset 76
        val lightOn = if (data.size > 76) {
            (data[76].toInt() and 0x02) != 0
        } else false

        val batteryPercent = ((battery1 + battery2) / 2f).toInt().coerceIn(0, 100)

        return WheelData(
            speed = speed,
            voltage = voltage,
            current = current,
            batteryPercent = batteryPercent,
            battery1Percent = battery1,
            battery2Percent = battery2,
            pwm = pwm,
            torque = torque,
            temperatures = temps,
            maxTemperature = temps.maxOrNull() ?: 0f,
            tripDistance = tripDistance / 1000f, // convert to km
            pitchAngle = pitchAngle,
            rollAngle = rollAngle,
            batteryPower = batteryPower,
            motorPower = motorPower,
            dynamicSpeedLimit = dynamicSpeedLimit,
            dynamicCurrentLimit = dynamicCurrentLimit,
            lightOn = lightOn,
            pcMode = pcMode,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Parse Settings response (command 0x20).
     * Data starts at offset 1 (data[0] is the sub-command echo).
     */
    fun parseSettings(data: ByteArray): WheelSettings? {
        if (data.size < 35) return null
        val d = if (data[0] == 0x20.toByte()) data.copyOfRange(1, data.size) else data

        val maxSpeedLimit = ByteUtils.getUint16LE(d, 0) / 100f
        val alarmSpeed = ByteUtils.getUint16LE(d, 2) / 100f
        val pedalAdjustment = ByteUtils.getInt16LE(d, 8) / 10f

        val offroadMode = (d[10].toInt() and 0x01) != 0
        val fancierMode = (d[10].toInt() and 0x10) != 0
        val comfortSensitivity = d[11].toInt() and 0xFF
        val classicSensitivity = d[12].toInt() and 0xFF
        val standbyDelay = ByteUtils.getUint16LE(d, 20) / 60 // minutes

        // Lock flag: byte 31 of d (byte 32 of raw packet, after 0x20 sub-cmd),
        // bit 2 (0x04) = locked. Verified from V14 live captures.
        val rawByte31 = if (d.size > 31) d[31].toInt() and 0xFF else 0
        val lockState = if ((rawByte31 and 0x04) != 0) 1 else 0

        val mute = (d[30].toInt() and 0x01) == 0
        val drl = (d[30].toInt() and 0x04) != 0
        val transportMode = (d[31].toInt() and 0x10) != 0

        return WheelSettings(
            maxSpeedKmh = maxSpeedLimit,
            alarmSpeedKmh = alarmSpeed,
            pedalAdjustment = pedalAdjustment,
            offroadMode = offroadMode,
            fancierMode = fancierMode,
            comfortSensitivity = comfortSensitivity,
            classicSensitivity = classicSensitivity,
            standbyDelayMinutes = standbyDelay,
            mute = mute,
            drl = drl,
            transportMode = transportMode,
            lockState = lockState
        )
    }

    /**
     * Parse CarType response (command 0x02, data[0]=0x01).
     */
    fun parseCarType(data: ByteArray): CarInfo? {
        if (data.size < 8) return null
        val mainSeries = data[0].toInt() and 0xFF
        val series = data[1].toInt() and 0xFF
        val type = data[2].toInt() and 0xFF
        val modelId = series * 10 + type
        val modelName = when (modelId) {
            91 -> "V14 50GB"
            92 -> "V14 50S"
            81 -> "V13"
            72 -> "V12 HT"
            71 -> "V12 HS"
            61 -> "V11"
            else -> "InMotion ($modelId)"
        }
        return CarInfo(series = series, type = type, modelId = modelId, modelName = modelName)
    }

    /**
     * Parse Versions response (command 0x02, data[0]=0x06).
     * Layout (offsets from data[0] which is sub-cmd echo 0x06):
     *   [2-3] DriverBoard patch (LE16), [4] minor, [5] major
     *   [6-7] unknown1 patch, [8] minor, [9] major
     *   [10] gap
     *   [11-12] MainBoard patch (LE16), [13] minor, [14] major
     *   [15] gap
     *   [16-17] unknown2 patch, [18] minor, [19] major
     *   [20-21] BLE patch (LE16), [22] minor, [23] major
     */
    fun parseVersions(data: ByteArray): FirmwareInfo? {
        if (data.size < 23) return null

        fun parseVersion(offset: Int): String {
            if (offset + 3 >= data.size) return "?.?.?"
            val patch = ByteUtils.getUint16LE(data, offset)
            val minor = data[offset + 2].toInt() and 0xFF
            val major = data[offset + 3].toInt() and 0xFF
            return "$major.$minor.$patch"
        }

        // Offsets relative to data (which starts after the 0x06 sub-cmd echo byte)
        // data[0] = unknown/padding
        // DriverBoard: patch@1, minor@3, major@4
        val driverBoard = parseVersion(1)
        // gap at offset 9
        // MainBoard: patch@10, minor@12, major@13
        val mainBoard = parseVersion(10)
        // gap at offset 14
        // BLE: patch@19, minor@21, major@22
        val ble = parseVersion(19)

        return FirmwareInfo(
            driverBoardVersion = driverBoard,
            mainBoardVersion = mainBoard,
            bleVersion = ble
        )
    }

    /**
     * Parse TotalStats response (command 0x11).
     */
    fun parseTotalStats(data: ByteArray): TotalStats? {
        if (data.size < 20) return null
        return TotalStats(
            totalDistanceM = ByteUtils.getUint32LE(data, 0) * 10,
            totalRideTimeS = ByteUtils.getUint32LE(data, 12),
            totalPowerOnTimeS = ByteUtils.getUint32LE(data, 16)
        )
    }
}

data class CarInfo(
    val series: Int,
    val type: Int,
    val modelId: Int,
    val modelName: String
)

data class FirmwareInfo(
    val driverBoardVersion: String,
    val mainBoardVersion: String,
    val bleVersion: String
) {
    val displayString: String get() = "Main:$mainBoardVersion Drv:$driverBoardVersion"
}

data class TotalStats(
    val totalDistanceM: Long,
    val totalRideTimeS: Long,
    val totalPowerOnTimeS: Long
) {
    val totalDistanceKm: Float get() = totalDistanceM / 1000f
}
