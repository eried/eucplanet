package com.eried.eucplanet.ble

/**
 * Settings read back from Veteran / NOSFET Page 8 telemetry frames (75 bytes, pageId == 8).
 *
 * Page 8 is emitted periodically alongside standard telemetry and smart-BMS slices on modern
 * firmware (LeaperKim and NOSFET wheels). Offsets 47..70 contain the wheel's active configuration
 * state.
 *
 * Sentinel handling:
 * Unsupported, unpopulated, or inactive fields report `0x80` (128 unsigned / -128 signed) on the
 * wire. These are mapped to null rather than zero-filled so callers can distinguish an explicit
 * zero setting from an unsupported feature.
 *
 * All multi-byte or signed ranges follow firmware scalar-bank definitions confirmed via APK and
 * EUC World disassembly, firmware analysis, and live BLE captures.
 */
data class VeteranPage8Settings(
    /** Headlight mode: 0 = off, 1 = low, 2 = medium, 3 = high. Null if unsupported (0x80). */
    val headlightMode: Int? = null,

    /** Continuous pedal hardness: 0..100%. Null if unsupported (0x80). */
    val pedalHardnessPercent: Int? = null,

    /** Tiltback speed threshold in km/h (typically 10..120 km/h, or 200 for disabled). Null if unsupported (0x80). */
    val tiltbackSpeedKmh: Int? = null,

    /** PWM tiltback duty threshold: 30..100% (or 200 for disabled). Null if unsupported (0x80). */
    val pwmTiltbackPercent: Int? = null,

    /** Speed alarm threshold in km/h (10..120 km/h). Null if unsupported (0x80). */
    val alarmSpeedKmh: Int? = null,

    /** Display screen backlight brightness: 0..100%. Null if unsupported (0x80). */
    val displayBrightnessPercent: Int? = null,

    /** Gyroscope / level calibration state: 0..2. Null if unsupported (0x80). */
    val gyroCalibrationState: Int? = null,

    /** Transport mode: false = normal (0), true = transport mode enabled (1). Null if unsupported (0x80). */
    val transportMode: Boolean? = null,

    /** Display speed units: 0 = km/h (metric), 1 = mph (imperial). Null if unsupported (0x80). */
    val displayUnits: Int? = null,

    /** Voltage adjustment in signed tenths of a percent (-15..+15, representing -1.5%..+1.5%). Null if unsupported (0x80). */
    val voltageAdjustmentTenths: Int? = null,

    /** Low-battery operating mode: 0 = disabled, 1 = enabled. Null if unsupported (0x80). */
    val lowBatteryMode: Int? = null,

    /** High-speed operating mode: 0 = disabled, 1 = enabled. Null if unsupported (0x80). */
    val highSpeedMode: Int? = null,

    /** Key-tone / button buzzer sound volume (SND): 0..100%. Null if unsupported (0x80). */
    val keyToneVolumePercent: Int? = null,

    /** Maximum charge voltage raw scalar: 0..120 (offset by voltage base). Null if unsupported (0x80). */
    val maxChargeVoltageRaw: Int? = null,

    /** Voltage base reference byte (e.g. 145 for 145V base / 36S, 121 for 121V base / 30S). Null if unsupported (0x80). */
    val voltageBase: Int? = null,

    /** Dynamic acceleration assist: 0..100% (ANG%). Null if unsupported (0x80). */
    val dynamicAssistPercent: Int? = null,

    /** Pedal dip compensation / recenter rate: 0..100% (ANG TLT). Null if unsupported (0x80). */
    val pedalDipCompensationPercent: Int? = null,

    /** Timestamp in nanoseconds when this page 8 frame was decoded. */
    val receivedAtNanos: Long = 0L,

    /** Raw 24-byte payload slice at offsets 47..70. Empty if not retained. */
    val rawPayload: ByteArray = ByteArray(0),
) {
    /**
     * Maximum charge voltage in volts, derived from [voltageBase] and [maxChargeVoltageRaw]
     * (formula: voltageBase + maxChargeVoltageRaw / 10f). Null if either component is missing.
     */
    val maxChargeVoltageVolts: Float?
        get() {
            val base = voltageBase ?: return null
            val raw = maxChargeVoltageRaw ?: return null
            return base + raw / 10f
        }

    /** Returns true if this settings frame was received within [maxAgeMs] milliseconds. */
    fun isFresh(nowNanos: Long = System.nanoTime(), maxAgeMs: Int = 10_000): Boolean =
        nowNanos - receivedAtNanos in 0..maxAgeMs.toLong() * 1_000_000L

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VeteranPage8Settings) return false
        return headlightMode == other.headlightMode &&
            pedalHardnessPercent == other.pedalHardnessPercent &&
            tiltbackSpeedKmh == other.tiltbackSpeedKmh &&
            pwmTiltbackPercent == other.pwmTiltbackPercent &&
            alarmSpeedKmh == other.alarmSpeedKmh &&
            displayBrightnessPercent == other.displayBrightnessPercent &&
            gyroCalibrationState == other.gyroCalibrationState &&
            transportMode == other.transportMode &&
            displayUnits == other.displayUnits &&
            voltageAdjustmentTenths == other.voltageAdjustmentTenths &&
            lowBatteryMode == other.lowBatteryMode &&
            highSpeedMode == other.highSpeedMode &&
            keyToneVolumePercent == other.keyToneVolumePercent &&
            maxChargeVoltageRaw == other.maxChargeVoltageRaw &&
            voltageBase == other.voltageBase &&
            dynamicAssistPercent == other.dynamicAssistPercent &&
            pedalDipCompensationPercent == other.pedalDipCompensationPercent &&
            receivedAtNanos == other.receivedAtNanos &&
            rawPayload.contentEquals(other.rawPayload)
    }

    override fun hashCode(): Int {
        var result = headlightMode?.hashCode() ?: 0
        result = 31 * result + (pedalHardnessPercent?.hashCode() ?: 0)
        result = 31 * result + (tiltbackSpeedKmh?.hashCode() ?: 0)
        result = 31 * result + (pwmTiltbackPercent?.hashCode() ?: 0)
        result = 31 * result + (alarmSpeedKmh?.hashCode() ?: 0)
        result = 31 * result + (displayBrightnessPercent?.hashCode() ?: 0)
        result = 31 * result + (gyroCalibrationState?.hashCode() ?: 0)
        result = 31 * result + (transportMode?.hashCode() ?: 0)
        result = 31 * result + (displayUnits?.hashCode() ?: 0)
        result = 31 * result + (voltageAdjustmentTenths?.hashCode() ?: 0)
        result = 31 * result + (lowBatteryMode?.hashCode() ?: 0)
        result = 31 * result + (highSpeedMode?.hashCode() ?: 0)
        result = 31 * result + (keyToneVolumePercent?.hashCode() ?: 0)
        result = 31 * result + (maxChargeVoltageRaw?.hashCode() ?: 0)
        result = 31 * result + (voltageBase?.hashCode() ?: 0)
        result = 31 * result + (dynamicAssistPercent?.hashCode() ?: 0)
        result = 31 * result + (pedalDipCompensationPercent?.hashCode() ?: 0)
        result = 31 * result + receivedAtNanos.hashCode()
        result = 31 * result + rawPayload.contentHashCode()
        return result
    }
}
