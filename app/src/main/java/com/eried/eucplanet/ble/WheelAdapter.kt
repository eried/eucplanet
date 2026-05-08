package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelSettings
import java.util.UUID

/**
 * BLE GATT profile a wheel adapter binds to. Each protocol family advertises a
 * different combination — InMotion V2 uses Nordic UART, InMotion V1 uses the
 * proprietary InMotion service, KingSong uses 0xFFE0/FFE1, Gotway/Veteran also
 * use 0xFFE0/FFE1 (disambiguated by first packet bytes after connect).
 *
 * The connection manager reads this from the active adapter on service discovery.
 */
data class BleProfile(
    val serviceUuid: UUID,
    /** Characteristic the adapter writes commands to. */
    val writeCharacteristic: UUID,
    /** Characteristic the wheel sends notifications on. */
    val notifyCharacteristic: UUID
) {
    companion object {
        /** Nordic UART used by the InMotion V2 family (V11/V12/V13/V14). */
        val NORDIC_UART = BleProfile(
            serviceUuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            writeCharacteristic = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
            notifyCharacteristic = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        )
    }
}

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

    /** BLE service + characteristic UUIDs the adapter binds to on connect. */
    fun bleProfile(): BleProfile = BleProfile.NORDIC_UART

    /**
     * Hook called once per connect attempt with the BLE advertised name (when
     * available). Adapters can use it to pre-select a model variant before the
     * first packet is sent — e.g., the InMotion P6 broadcasts as `P6-XXXXXXXX`
     * and uses an extended-routing-only command set, so we pick the P6 code
     * path before the legacy carType query reaches the wheel and times out.
     * Default is a no-op.
     */
    fun notifyConnectingTo(deviceName: String?) {}

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

    /**
     * Optional second packet to send right after [setMaxSpeed], used by the P6
     * to commit the new tiltback to flash via `60 3e [val 00 00]`. Return null
     * for wheels that persist the change in a single write.
     */
    fun setMaxSpeedCommit(tiltbackKmh: Float): ByteArray? = null

    fun setVolume(percent: Int): ByteArray?
    fun setDRL(on: Boolean): ByteArray?
    fun setLock(locked: Boolean): ByteArray?

    // --- V14-style password auth. Adapters without auth return null. ---
    fun requestAuthKey(): ByteArray?
    fun verifyAuth(encryptedKey: ByteArray): ByteArray?

    /**
     * Process a raw BLE notification and return zero or more decoded results.
     *
     * Each protocol family handles its own framing here — InMotion V2 reassembles
     * packets across notifications and parses the AA AA … XOR-checksum frame,
     * KingSong reads a fixed 20-byte structure, Veteran walks the DC 5A 5C state
     * machine, etc. Returning a list lets one notification carry multiple frames
     * (uncommon but possible when the wheel batches them tighter than the MTU).
     *
     * Adapters with persistent framing state (reassembly buffers) reset it in
     * [onDisconnect]; the connection manager guarantees that hook is called.
     */
    fun onRawNotification(rawBytes: ByteArray): List<DecodeResult>

    /**
     * Called by the connection manager on disconnect so adapters can reset any
     * connection-scoped state (reassembly buffers, detected model, etc.). Default
     * is a no-op for stateless adapters.
     */
    fun onDisconnect() {}
}

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
