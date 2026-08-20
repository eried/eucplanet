package com.eried.eucplanet.ble

/**
 * Whether to chase a wheel that has gone away.
 *
 * Getting this wrong goes badly in both directions. Too shy and a rider whose
 * wheel blipped out mid-ride finishes with no telemetry and no trip. Too eager
 * and the app reconnects to a wheel the rider deliberately disconnected,
 * waking it, holding the link and draining both batteries.
 *
 * The distinction the stack gives us is the GATT status on the disconnect
 * callback: 0 means the connection was closed on purpose, anything else means
 * it broke. That is the whole basis of the rule, so it is stated here where it
 * can be tested rather than inside a callback that needs a live adapter.
 */
object ReconnectPolicy {

    /** A GATT disconnect with this status was asked for, not suffered. */
    const val STATUS_CLEAN = 0

    /**
     * Whether a disconnect callback should lead to a reconnect attempt.
     *
     * @param armed auto-reconnect is on, i.e. the rider has not disconnected
     *   deliberately since the last connect
     * @param target the address being watched, null when nothing is armed
     * @param status the GATT status from the disconnect callback
     */
    fun shouldRetryAfterDisconnect(armed: Boolean, target: String?, status: Int): Boolean =
        armed && target != null && status != STATUS_CLEAN

    /**
     * Whether a reconnect attempt is still worth making by the time it runs.
     *
     * Attempts are delayed - the BLE stack is not ready the instant Bluetooth
     * reports itself on - so everything can have changed in between: the rider
     * may have disconnected, connected to another wheel, or turned Bluetooth
     * off again.
     */
    fun eligible(
        armed: Boolean,
        target: String?,
        address: String,
        connected: Boolean,
        adapterOn: Boolean,
    ): Boolean = armed && target == address && !connected && adapterOn
}
