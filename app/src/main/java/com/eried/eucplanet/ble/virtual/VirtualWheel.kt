package com.eried.eucplanet.ble.virtual

/**
 * In-app fake wheel that bypasses real BLE entirely. Selected from the scan
 * screen via the "Simulate" entry; the connection manager skips GATT and
 * routes writes/reads through whichever VirtualWheel is active.
 *
 * The fake's job is to produce raw bytes that look exactly like notifications
 * a real wheel would emit on the BLE channel: same framing, same field
 * layouts. Those bytes then flow through the unchanged adapter pipeline
 * ([com.eried.eucplanet.ble.WheelAdapter.onRawNotification]) so we exercise
 * the real parser code, not a shortcut. This catches off-by-one offsets in
 * our parsers before any wheel hardware ever sees the build.
 *
 * This is debug-only infrastructure; the scan-screen entry that creates one
 * is gated behind BuildConfig.DEBUG.
 */
interface VirtualWheel {
    /** User-visible name on the scan screen. */
    val displayName: String

    /** A short stable id, used as the "VIRTUAL:<id>" pseudo-address. */
    val id: String

    /**
     * BLE-advertised name handed to the [com.eried.eucplanet.ble.WheelAdapter]
     * so the adapter dispatcher routes the simulated wheel to the right family
     * (e.g. "Master_VIRTUAL" → Begode). If empty, the dispatcher falls back to
     * its InMotion V2 default which is correct for V14 / P6 simulators.
     */
    val bleName: String get() = ""

    /**
     * Notification payloads to emit in order on connect, before the polling
     * loop starts. Most simulators leave this empty and respond inside
     * [onWrite] when the app sends getCarType / getVersions / etc.
     */
    fun onConnect(): List<ByteArray> = emptyList()

    /**
     * Called when the app writes a command. Return zero or more notification
     * payloads to emit back. For InMotion V2 the wheel typically responds
     * once per query (carType, versions, settings, telemetry, etc.); match
     * that pattern.
     */
    fun onWrite(data: ByteArray): List<ByteArray>

    /**
     * Called periodically (default 250 ms) while connected. Use sparingly;
     * most wheels respond only when polled, so most simulators leave this
     * empty and let [onWrite] drive responses.
     */
    fun onTick(elapsedMs: Long): List<ByteArray> = emptyList()

    /** Reset internal state for a new "connection". Called by the manager. */
    fun reset() {}
}
