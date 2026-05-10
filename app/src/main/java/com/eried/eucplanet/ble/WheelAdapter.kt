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

        /**
         * HM-10 / JNHuaMao profile shared by KingSong, Begode/Gotway and
         * Veteran wheels. Same service+characteristic UUIDs across all
         * three brands; the wheel is identified post-connect by sniffing
         * the first frame's magic bytes (`AA 55` = KingSong, `55 AA` =
         * Begode, `DC 5A 5C` = Veteran).
         */
        val HM10 = BleProfile(
            serviceUuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
            writeCharacteristic = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
            notifyCharacteristic = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        )

        /**
         * InMotion V1 (V5 / V8 / V10 / L6 / R-series / V3) — proprietary
         * 0xFFEx profile split across two services. Notify characteristic
         * 0xFFE4 lives under service 0xFFE0; write characteristic 0xFFE9
         * lives under service 0xFFE5. Distinct from KingSong / Begode /
         * Veteran (all single-service 0xFFE0 / 0xFFE1) and from V2 (Nordic
         * UART). See docs/protocols/inmotion_v1.md section 1.
         */
        val INMOTION_V1 = BleProfile(
            serviceUuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
            writeCharacteristic = UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb"),
            notifyCharacteristic = UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb")
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
     *
     * Returns a [DecodeResult.ModelName] when the name alone is enough to
     * identify the wheel; the BLE layer emits it immediately so the slider
     * cap and other model-keyed UI bits don't have to wait for the wheel's
     * own info-bundle round-trip. Default returns null.
     */
    fun notifyConnectingTo(deviceName: String?): DecodeResult.ModelName? = null

    /** Packets sent in order on first connect, before the realtime poll loop starts. */
    fun initSequence(): List<ByteArray>

    /** Sent every 250 ms during the polling loop. */
    fun pollRealtime(): ByteArray

    /** Sent occasionally during the polling loop to refresh wheel-side settings. */
    fun pollSettings(): ByteArray

    /** Sent occasionally during the polling loop to refresh extended stats
     *  (P6: the totalStats / Detailed Data response that carries motor and
     *  driver-board temperatures). Return null if the wheel doesn't have
     *  this query. */
    fun pollStats(): ByteArray? = null

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

    /**
     * Optional third packet for the P6 — sets the alarm-speed threshold
     * separately via the same `60 3e` flash-commit opcode. Returns null
     * for wheels where the alarm threshold ships in the [setMaxSpeed]
     * packet (V14 family) or where alarm isn't a separate setting.
     */
    fun setAlarmSpeedCommit(alarmKmh: Float): ByteArray? = null

    fun setVolume(percent: Int): ByteArray?
    fun setDRL(on: Boolean): ByteArray?
    fun setLock(locked: Boolean): ByteArray?

    // --- V14-style password auth. Adapters without auth return null. ---
    fun requestAuthKey(): ByteArray?
    fun verifyAuth(encryptedKey: ByteArray): ByteArray?

    /**
     * Whether the wheel needs the password auth handshake to be run once
     * right after [initSequence] finishes, before any control writes. Set to
     * true for wheels where the firmware silently drops control commands
     * (light, horn, max-speed) until the connect-time handshake completes.
     *
     * Confirmed cases:
     *  - InMotion P6: requires auth at connect, otherwise the dashboard
     *    Light / Auto Headlight toggles look successful at the L2CAP layer
     *    but the wheel never obeys.
     *
     * Default false (no extra writes); the lock path runs auth on demand
     * via [requestAuthKey] / [verifyAuth] regardless of this flag.
     */
    fun requiresConnectAuth(): Boolean = false

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

    /**
     * Per-wheel diagnostic test commands shown in the Wheel Diagnostics dialog
     * (Service Mode). Each entry becomes a tappable button — the user taps to
     * fire the bytes and watches the live log to see what the wheel does. This
     * is how we narrow down opcodes the wheel actually obeys vs. ones that
     * look right on paper but get silently dropped.
     *
     * The default empty list keeps wheels with no diagnostic guesses out of
     * the dialog; adapters override when they have hypotheses to test.
     */
    fun getDiagnosticCommands(): List<com.eried.eucplanet.diagnostics.DiagnosticCommand> = emptyList()

    /**
     * Friendly name for the wheel family. Used in Service Mode's wheel-family
     * pickers so the user can browse any family's catalogue regardless of
     * what's actually connected. Defaults to [familyId].
     */
    val familyDisplayName: String get() = familyId

    /**
     * Service Mode "Inspect" tab subscribes to NOTE entries whose text starts
     * with one of these prefixes. Adapters that log realtime / detail bodies
     * via DiagnosticsLogger.note() should list those prefixes here so the
     * picker offers them. Default empty.
     */
    fun inspectMessageTypes(): List<String> = emptyList()
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
    /** Out-of-band sensor block from the P6's `0x84` detailed-data response.
     *  Carries MOS / motor / driver-board temperatures in °C. */
    data class P6Temperatures(
        val mosC: Float?,
        val motorC: Float?,
        val driverBoardC: Float?
    ) : DecodeResult()
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

        /**
         * InMotion V1 family (V5 / V8 / V10 / L6 / Glide 3 / R-series).
         * Horn and headlight are universal. Volume + DRL are firmware-
         * dependent (V8F / V8S / V10 family / Glide 3 only); the adapter
         * narrows them per detected model. No remote lock command — lock
         * state is observable in work mode but not commandable. Alarm
         * speed is not user-configurable on V1; alarms are firmware
         * tilt-back triggers reported via the async alert frame.
         */
        val INMOTION_V1 = WheelCapabilities(
            hasHorn = true,
            hasLight = true,
            hasLock = false,
            hasMaxSpeed = true,
            hasAlarmSpeed = false,
            hasVolume = true,
            hasDRL = true,
            needsAuthForLock = false
        )

        /** KingSong KS-* wheels — no software lock, no volume control. */
        val KINGSONG = WheelCapabilities(
            hasHorn = true,
            hasLight = true,
            hasLock = false,
            hasMaxSpeed = true,
            hasAlarmSpeed = true,
            hasVolume = false,
            hasDRL = false,
            needsAuthForLock = false
        )

        /**
         * Begode/Gotway — no software lock (dismount only), no native
         * volume control. Light is a 3-state (off/dim/full); the adapter
         * collapses dim to off for the on/off toggle.
         */
        val BEGODE = WheelCapabilities(
            hasHorn = true,
            hasLight = true,
            hasLock = false,
            hasMaxSpeed = true,
            hasAlarmSpeed = true,
            hasVolume = false,
            hasDRL = false,
            needsAuthForLock = false
        )

        /**
         * Veteran — minimal control surface. Telemetry is rich (cells,
         * BMS) but writes are limited to horn, light on/off and a few
         * threshold setters.
         */
        val VETERAN = WheelCapabilities(
            hasHorn = true,
            hasLight = true,
            hasLock = false,
            hasMaxSpeed = false,
            hasAlarmSpeed = false,
            hasVolume = false,
            hasDRL = false,
            needsAuthForLock = false
        )

        /**
         * Ninebot Z (Z6 / Z10 / new-stack E+ / Mini Plus) — full settings
         * surface. No documented horn opcode but lock, speed limit, three
         * alarm slots, LED, volume, and DRL via DriveFlags bit 0 are all
         * writable. The wheel does NOT enforce a PIN for lock; the
         * encrypted handshake is the security gate, so [needsAuthForLock]
         * stays false here. Spec section 19.
         */
        val NINEBOT_Z = WheelCapabilities(
            hasHorn = false,
            hasLight = true,
            hasLock = true,
            hasMaxSpeed = true,
            hasAlarmSpeed = true,
            hasVolume = true,
            hasDRL = true,
            needsAuthForLock = false
        )

        /**
         * Ninebot legacy (One E / E+ / S2 / Mini / Mini Pro) — read-only
         * telemetry over BLE. The legacy stack does not expose lock,
         * alarms, lights, volume, or LED through any documented parameter
         * (spec section 16). Settings changes require the official Ninebot
         * app over a side channel we don't cover.
         */
        val NINEBOT_LEGACY = WheelCapabilities(
            hasHorn = false,
            hasLight = false,
            hasLock = false,
            hasMaxSpeed = false,
            hasAlarmSpeed = false,
            hasVolume = false,
            hasDRL = false,
            needsAuthForLock = false
        )
    }
}
