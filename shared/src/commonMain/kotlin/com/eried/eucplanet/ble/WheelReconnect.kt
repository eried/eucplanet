package com.eried.eucplanet.ble

/**
 * The wheel's reconnect hunt, as both phones run it: when to chase a wheel
 * that dropped, how long each scan for it lasts, the growing pauses between
 * scans, and when a long hunt drops to a low-power scan. Android's
 * BleConnectionManager reads these numbers and rules; the iPhone runs them
 * through [WheelReconnectHunt].
 */
object WheelReconnect {
    /** After a wheel drops: the wait before the hunt starts, while it resumes advertising. */
    const val DROP_DELAY_MS = 2_000L

    /** After Bluetooth comes back on: the stack is not ready the instant it says so. */
    const val BLUETOOTH_ON_SETTLE_MS = 1_500L

    /** While the scan screen is open a held reconnect checks back this often. */
    const val SUPPRESSED_POLL_MS = 300L

    /**
     * One reconnect-scan window. Bounded so a full-speed scan is never held
     * open forever (Android downgrades or blocks abusive scanners).
     */
    const val SCAN_WINDOW_MS = 60_000L

    /**
     * Pauses between scan windows. Grows so a wheel that is genuinely gone
     * (powered off, left at home) costs little battery, while one that comes
     * back mid-ride is still caught.
     */
    val SCAN_PAUSES_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L)

    /**
     * How long a hunt scans at full speed. After that the wheel is most
     * likely off or left behind, so it drops to a low-power scan: a wheel
     * coming back is still found, a few seconds later. Opening the app
     * restarts the full-speed hunt.
     */
    const val LOW_POWER_AFTER_MS = 10 * 60_000L

    /** How often the link's signal strength is read while connected. */
    const val RSSI_POLL_MS = 1_000L

    /** The pause after scan window number [cycle] (0 based). */
    fun pauseAfterWindow(cycle: Int): Long = SCAN_PAUSES_MS[minOf(cycle, SCAN_PAUSES_MS.lastIndex)]

    /** True once a hunt that began at [huntStartMs] has run its full-speed stretch. */
    fun lowPowerDue(nowMs: Long, huntStartMs: Long): Boolean = nowMs - huntStartMs >= LOW_POWER_AFTER_MS

    /**
     * The delayed reconnect after a drop, once the scan screen has closed:
     * still armed, still the same wheel, and nothing else has started since.
     */
    fun huntAfterDrop(armed: Boolean, target: String?, address: String?, state: ConnectionState): Boolean =
        armed && address != null && target == address && state == ConnectionState.DISCONNECTED

    /** The delayed reconnect after Bluetooth came back on. */
    fun huntAfterBluetoothOn(
        armed: Boolean,
        suppressed: Boolean,
        target: String?,
        address: String,
        state: ConnectionState,
        adapterOn: Boolean,
    ): Boolean = armed && !suppressed && target == address && state != ConnectionState.CONNECTED && adapterOn

    /** The next scan window, after a pause. */
    fun huntAgain(armed: Boolean, suppressed: Boolean, target: String?, address: String, state: ConnectionState): Boolean =
        armed && !suppressed && target == address && state == ConnectionState.DISCONNECTED

    /**
     * The rider opened the app while a hunt idles between scans or has dropped
     * to low power: scan at full speed again, starting the hunt over.
     */
    fun restartOnForeground(
        armed: Boolean,
        suppressed: Boolean,
        hunting: Boolean,
        hasTarget: Boolean,
        adapterOn: Boolean,
        state: ConnectionState,
        lowPower: Boolean,
    ): Boolean = armed && !suppressed && hunting && hasTarget && adapterOn &&
        (state == ConnectionState.DISCONNECTED || (state == ConnectionState.SCANNING && lowPower))
}
