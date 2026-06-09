package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelSettings

/**
 * Per-protocol-family wheel adapter. Each BLE-protocol family (InMotion V2, V1,
 * KingSong, Gotway, Veteran, ...) has one implementation; the repository talks
 * only to this interface so the same UI can drive any wheel.
 *
 * Methods that don't apply to a given family return null; the repository checks
 * and skips silently. The UI consults [capabilities] to gray out unsupported
 * actions.
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
     * first packet is sent, e.g., the InMotion P6 broadcasts as `P6-XXXXXXXX`
     * and uses an extended-routing-only command set.
     *
     * Returns a [DecodeResult.ModelName] when the name alone is enough to
     * identify the wheel; the BLE layer emits it immediately. Default null.
     */
    fun notifyConnectingTo(deviceName: String?): DecodeResult.ModelName? = null

    /**
     * Post-connect adapter rescue. The BLE layer calls this when the active
     * adapter's [bleProfile] service is NOT among the wheel's discovered GATT
     * services, signalling the name-based pre-selection was wrong.
     *
     * The dispatcher walks the discovered service UUIDs (lowercase-canonical
     * strings) and may re-route the active sub-adapter to whichever family
     * matches. Returns true if the new active adapter's service is in the
     * wheel's GATT tree; false if no known adapter matches.
     *
     * Default: no-op returning false. Only [CompositeWheelAdapter] overrides.
     */
    fun pickAdapterByDiscoveredServices(
        discoveredServiceUuids: Set<String>,
        deviceName: String?
    ): Boolean = false

    /** Packets sent in order on first connect, before the realtime poll loop starts. */
    fun initSequence(): List<ByteArray>

    /** Sent every 250 ms during the polling loop. */
    fun pollRealtime(): ByteArray

    /** Sent occasionally during the polling loop to refresh wheel-side settings. */
    fun pollSettings(): ByteArray

    /** Sent occasionally to refresh extended stats (P6 motor / driver-board
     *  temperatures). Return null if the wheel doesn't have this query. */
    fun pollStats(): ByteArray? = null

    // --- Control commands. Return null if the wheel doesn't support the action. ---
    fun horn(): ByteArray?

    /**
     * Optional second frame sent immediately after [horn]. Veteran's current
     * (Lynx-class) firmware only beeps when the `LkAp` horn frame is followed
     * by an `LdAp` companion frame. Default null.
     */
    fun hornFollowup(): ByteArray? = null

    fun setLight(on: Boolean): ByteArray?

    /**
     * Optional second frame sent immediately after [setLight], for families
     * whose headlight command is a two-frame write (e.g. Veteran high beam).
     * Default null (single-write headlight).
     */
    fun setLightFollowup(on: Boolean): ByteArray? = null

    fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray?

    /**
     * Optional second packet after [setMaxSpeed], used by the P6 to commit the
     * new tiltback to flash. Return null for single-write wheels.
     */
    fun setMaxSpeedCommit(tiltbackKmh: Float): ByteArray? = null

    /**
     * Optional third packet for the P6: sets the alarm-speed threshold
     * separately. Returns null where the alarm ships in [setMaxSpeed] (V14).
     */
    fun setAlarmSpeedCommit(alarmKmh: Float): ByteArray? = null

    fun setVolume(percent: Int): ByteArray?
    fun setDRL(on: Boolean): ByteArray?
    fun setLock(locked: Boolean): ByteArray?

    /**
     * Resets the wheel's onboard trip meter. Returns null on wheels where the
     * protocol has no documented reset command. Veteran is the only family with
     * a public command today (CLEARMETER).
     */
    fun resetTripMeter(): ByteArray? = null

    // --- V14-style password auth. Adapters without auth return null. ---
    fun requestAuthKey(): ByteArray?
    fun verifyAuth(encryptedKey: ByteArray): ByteArray?

    /**
     * Whether the wheel needs the password auth handshake run once right after
     * [initSequence], before any control writes (confirmed: InMotion P6).
     * Default false.
     */
    fun requiresConnectAuth(): Boolean = false

    /**
     * Process a raw BLE notification and return zero or more decoded results.
     * Each protocol family handles its own framing here. Adapters with persistent
     * framing state reset it in [onDisconnect].
     */
    fun onRawNotification(rawBytes: ByteArray): List<DecodeResult>

    /**
     * Called by the connection manager on disconnect so adapters can reset any
     * connection-scoped state (reassembly buffers, detected model, etc.).
     */
    fun onDisconnect() {}

    /**
     * Per-wheel diagnostic test commands shown in the Wheel Diagnostics dialog
     * (Service Mode). The default empty list keeps wheels with no diagnostic
     * guesses out of the dialog; adapters override when they have hypotheses.
     */
    fun getDiagnosticCommands(): List<com.eried.eucplanet.diagnostics.DiagnosticCommand> = emptyList()

    /**
     * Friendly name for the wheel family, used in Service Mode's pickers.
     * Defaults to [familyId].
     */
    val familyDisplayName: String get() = familyId

    /**
     * Brand the connected wheel belongs to, e.g. "InMotion" / "Begode".
     * Derived from [familyId]. The dashboard can show it as the wheel name.
     */
    val brand: String get() = when (familyId) {
        "begode" -> "Begode"
        "kingsong" -> "KingSong"
        "veteran" -> "Veteran"
        "ninebot" -> "Ninebot"
        "inmotion_v1", "inmotion_v2" -> "InMotion"
        else -> familyDisplayName
    }

    /**
     * Service Mode "Inspect" tab subscribes to NOTE entries whose text starts
     * with one of these prefixes. Default empty.
     */
    fun inspectMessageTypes(): List<String> = emptyList()
}
