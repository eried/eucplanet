package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelSettings

/**
 * Output of [WheelAdapter.decode]. The repository switches on this to update its
 * state flows (telemetry / settings / identity / auth state).
 */
sealed class DecodeResult {
    data class Telemetry(val data: WheelData) : DecodeResult()
    data class Settings(val data: WheelSettings) : DecodeResult()
    /**
     * Wheel-reported identity. [model] is the brand-specific identifier (currently
     * [InMotionV2Model], later includes V1 / KingSong / Veteran), null if the wheel
     * reports an ID we don't recognize. Adapters keep [name] populated regardless
     * for display.
     */
    data class ModelName(val name: String, val model: Any? = null) : DecodeResult()
    data class Firmware(val display: String, val mainBoard: String, val driverBoard: String, val ble: String) : DecodeResult()
    data class TotalDistance(val km: Float) : DecodeResult()
    data class AuthKey(val encryptedKey: ByteArray) : DecodeResult() {
        override fun equals(other: Any?): Boolean =
            other is AuthKey && encryptedKey.contentEquals(other.encryptedKey)
        override fun hashCode(): Int = encryptedKey.contentHashCode()
    }
    data class AuthConfirm(val success: Boolean) : DecodeResult()
    /** Out-of-band sensor block from the P6's `0x84` detailed-data response.
     *  Carries MOS / motor / driver-board temperatures in °C. */
    data class P6Temperatures(
        val mosC: Float?,
        val motorC: Float?,
        val driverBoardC: Float?
    ) : DecodeResult()
    /** A smart-BMS sub-frame slice (cells / temps / pack current) — stitched into
     *  the running BmsState by [com.eried.eucplanet.data.mergeBmsSlice]. */
    data class Bms(
        val packIndex: Int,
        val cellVoltages: List<Float>? = null,
        val cellRangeStart: Int? = null,
        val bmsTempsC: List<Float>? = null,
        val packCurrent1A: Float? = null,
        val packCurrent2A: Float? = null,
    ) : DecodeResult()
    data object Unknown : DecodeResult()
}
