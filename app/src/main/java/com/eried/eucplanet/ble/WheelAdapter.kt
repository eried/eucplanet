package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelSettings

/**
 * Per-protocol-family wheel adapter. Each BLE-protocol family (InMotion V2, V1,
 * KingSong, Gotway, Veteran, ...) has one implementation; the repository talks
 * only to this interface so the same UI can drive any wheel.
 *
 * Phase 1 ships with InMotionV2Adapter (V14 only). Later phases add V1 (V10F),
 * KingSong (S18), Veteran (Lynx) etc. Methods that don't apply to a given family
 * return null — the repository checks and skips silently. The UI consults
 * [capabilities] to gray out unsupported actions.
 *
 * Auth state for lock/unlock (V14-specific) is exposed as [requestAuthKey] /
 * [verifyAuth] returning packets; the repository drives the handshake. Adapters
 * without auth return null for both, and the repository's lock path handles that.
 */
interface WheelAdapter {
    val familyId: String
    val capabilities: WheelCapabilities

    /** Packets sent in order on first connect, before the realtime poll loop starts. */
    fun initSequence(): List<ByteArray>

    /** Sent every 250 ms during the polling loop. */
    fun pollRealtime(): ByteArray

    /** Sent occasionally during the polling loop to refresh wheel-side settings. */
    fun pollSettings(): ByteArray

    // --- Control commands. Return null if the wheel doesn't support the action. ---
    fun horn(): ByteArray?
    fun setLight(on: Boolean): ByteArray?
    fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray?
    fun setVolume(percent: Int): ByteArray?
    fun setDRL(on: Boolean): ByteArray?
    fun setLock(locked: Boolean): ByteArray?

    // --- V14-style password auth. Adapters without auth return null. ---
    fun requestAuthKey(): ByteArray?
    fun verifyAuth(encryptedKey: ByteArray): ByteArray?

    /** Decode a parsed BLE packet. Returns the appropriate DecodeResult variant. */
    fun decode(command: Byte, data: ByteArray): DecodeResult
}

/**
 * Output of [WheelAdapter.decode]. The repository switches on this to update its
 * state flows (telemetry / settings / identity / auth state).
 */
sealed class DecodeResult {
    data class Telemetry(val data: WheelData) : DecodeResult()
    data class Settings(val data: WheelSettings) : DecodeResult()
    data class ModelName(val name: String) : DecodeResult()
    data class Firmware(val display: String, val mainBoard: String, val driverBoard: String, val ble: String) : DecodeResult()
    data class TotalDistance(val km: Float) : DecodeResult()
    data class AuthKey(val encryptedKey: ByteArray) : DecodeResult() {
        override fun equals(other: Any?): Boolean =
            other is AuthKey && encryptedKey.contentEquals(other.encryptedKey)
        override fun hashCode(): Int = encryptedKey.contentHashCode()
    }
    data class AuthConfirm(val success: Boolean) : DecodeResult()
    data object Unknown : DecodeResult()
}

/**
 * What features a given wheel exposes via BLE. The UI consults this to gray out
 * action buttons and hide settings sections that don't apply to the connected
 * wheel. New capabilities should only be added when at least one wheel supports
 * them — empty defaults keep new adapters honest about declaring support.
 */
data class WheelCapabilities(
    val hasHorn: Boolean = false,
    val hasLight: Boolean = false,
    val hasLock: Boolean = false,
    val hasMaxSpeed: Boolean = false,
    val hasAlarmSpeed: Boolean = false,
    val hasVolume: Boolean = false,
    val hasDRL: Boolean = false,
    val needsAuthForLock: Boolean = false
) {
    companion object {
        /** V11/V12/V13/V14 — full feature set, lock requires password auth. */
        val INMOTION_V2 = WheelCapabilities(
            hasHorn = true,
            hasLight = true,
            hasLock = true,
            hasMaxSpeed = true,
            hasAlarmSpeed = true,
            hasVolume = true,
            hasDRL = true,
            needsAuthForLock = true
        )
    }
}
