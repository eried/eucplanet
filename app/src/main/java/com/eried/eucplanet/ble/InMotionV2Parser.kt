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
        val model = InMotionV2Model.fromId(modelId)
        val modelName = model?.displayName ?: "InMotion ($modelId)"
        return CarInfo(series = series, type = type, modelId = modelId, modelName = modelName, model = model)
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
     * Parse P6 realtime telemetry from the data block of a `21 02 87 01 00 …`
     * response. The data passed in here is the part *after* the `21 02 87 01 00`
     * routing prefix — exactly the bytes the wheel reports for each sample.
     *
     * What we trust today: voltage and current at offsets 0/2 match the InMotion
     * app's reported values across all captures (Voltage 230 V, Current ≈ 0 A
     * while parked). Battery percent is derived linearly from voltage on the
     * 56s pack curve (3.0–4.2 V per cell → 165–235 V end-to-end), which is
     * rough but lets the dashboard ring fill in.
     *
     * Everything else (speed, PWM, temperatures, trip distance, etc.) sits at
     * different offsets than V14 and we don't have labelled riding captures yet.
     * Those fields stay at defaults — the dashboard reads blank for them, which
     * is honest about what we can't yet decode.
     */
    fun parseP6Telemetry(data: ByteArray): WheelData? {
        if (data.size < 4) return null
        val voltage = ByteUtils.getUint16LE(data, 0) / 100f
        val current = ByteUtils.getInt16LE(data, 2) / 100f

        // Speed at offset 8-9: uint16 LE in 0.01 km/h. Confirmed via the
        // labelled riding capture — at video 6:15 = wall-clock 13:30:25,
        // labelled "16 mph", offset 8-9 reads 2650 = 26.50 km/h = 16.5 mph.
        // Stays at 0 across every parked frame.
        val speed = if (data.size >= 10) ByteUtils.getUint16LE(data, 8) / 100f else 0f

        // PWM at offset 12-13: int16 LE in 0.01% (signed for regen braking).
        // Confirmed against the same capture — heavy acceleration in the
        // 42 mph ramp shows 7775 = 77.75%, hard regen brake shows
        // -9584 = -95.84% (the wheel pulling current back into the pack).
        val pwm = if (data.size >= 14) ByteUtils.getInt16LE(data, 12) / 100f else 0f

        // Real per-pack battery percent at offsets 20-23 of the data block
        // (98.94 / 96.90 in the real-P6 capture, matched the on-screen 98%).
        // Falls back to a voltage estimate while frames are still partial.
        val battery1 = if (data.size >= 22) ByteUtils.getUint16LE(data, 20) / 100f else 0f
        val battery2 = if (data.size >= 24) ByteUtils.getUint16LE(data, 22) / 100f else 0f
        val batteryPercent = if (battery1 > 0f || battery2 > 0f) {
            ((battery1 + battery2) / 2f).toInt().coerceIn(0, 100)
        } else {
            ((voltage - 165f) / 70f * 100f).toInt().coerceIn(0, 100)
        }

        // Total mileage as uint32 LE at offset 58, in 0.01 km units.
        // Confirmed across three labelled riding moments (1776.8 / 1776.9 /
        // 1777.0 mi displayed by the InMotion app, 285958 / 285970 / 285990
        // in the bytes — within rounding of the displayed value).
        val tripDistanceKm = if (data.size >= 62) {
            ByteUtils.getUint32LE(data, 58) / 100f
        } else 0f

        // Park vs Drive: offset 68 holds 0x0f whenever the wheel is
        // stationary (regardless of selected ride mode), and a different
        // value (seen 0x95 / 0x96 / 0xfe) when the wheel is actively
        // engaged in S/Comfort. Matches the V14's pcMode role: 0 = park,
        // anything else = drive engaged. Distinguishing Sport vs Comfort
        // sub-states would need a separate labelled capture; for now this
        // gives the dashboard P-vs-D parity with V14.
        val pcMode = if (data.size > 68 && (data[68].toInt() and 0xFF) != 0x0F) 1 else 0

        return WheelData(
            speed = speed,
            voltage = voltage,
            current = current,
            pwm = pwm,
            pcMode = pcMode,
            batteryPercent = batteryPercent,
            battery1Percent = battery1.takeIf { it > 0f } ?: batteryPercent.toFloat(),
            battery2Percent = battery2.takeIf { it > 0f } ?: batteryPercent.toFloat(),
            tripDistance = tripDistanceKm,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Parse the P6 settings response (sub 0x20 with arg 0x20). Layout
     * confirmed against a labelled InMotion-app capture (see
     * docs/P6_CAPTURE_LABELS.md). The 51-byte body, after stripping the
     * leading 0x20 sub-cmd echo:
     *
     *   d[8..9]   tiltback set speed     uint16 LE / 100 = km/h
     *   d[10..11] speed limit alarm      uint16 LE / 100 = km/h
     *   d[14..15] PWM tilt-back limit    uint16 LE / 100 = %
     *   d[16..17] PWM level 1 alarm      uint16 LE / 100 = %
     *   d[18..19] PWM level 2 alarm      uint16 LE / 100 = %
     *
     * Earlier builds read tiltback at d[12..13] which only worked when
     * the user's tiltback equalled their alarm (the value at d[8..9]
     * happened to look like a "mirror" of d[10..11] in that test set).
     */
    fun parseP6Settings(data: ByteArray): WheelSettings? {
        if (data.size < 21) return null
        // First byte echoes the sub-cmd (0x20). Skip it.
        val d = if (data[0] == 0x20.toByte()) data.copyOfRange(1, data.size) else data
        if (d.size < 20) return null
        val tiltback = ByteUtils.getUint16LE(d, 8) / 100f
        val alarm = ByteUtils.getUint16LE(d, 10) / 100f
        return WheelSettings(
            maxSpeedKmh = tiltback,
            alarmSpeedKmh = alarm
        )
    }

    /**
     * Extract the ASCII serial from the data block of a `21 02 86 01 00 …` info
     * bundle response. Returns null if the layout doesn't match what we've seen.
     */
    fun parseP6Serial(data: ByteArray): String? {
        if (data.size < 17 || data[0] != 0x01.toByte()) return null
        val serialBytes = data.copyOfRange(1, 17)
        // Trim trailing nulls and spaces so we don't render junk in the UI.
        return String(serialBytes, Charsets.US_ASCII)
            .trimEnd { it == ' ' || it.code == 0 }
            .ifBlank { null }
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
    val modelName: String,
    /** Resolved model from the WheelLog registry, or null if the wheel reports an unknown ID. */
    val model: InMotionV2Model? = null
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
