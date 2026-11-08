package com.eried.eucplanet.ble

/** Runs [block] after [delayMs]; the platform's main-thread timer. */
fun interface HuntTimer {
    fun after(delayMs: Long, block: () -> Unit)
}

/** What the hunt asks of the link it runs on. */
interface HuntRadio {
    /** The phone's Bluetooth is on. */
    val adapterOn: Boolean

    /** The link's state, which the hunt reads and sets (SCANNING, DISCONNECTED). */
    var state: ConnectionState

    /** Starts scanning for [address]; false when the phone cannot scan, so the hunt connects straight away. */
    fun startHuntScan(address: String, lowPower: Boolean): Boolean

    fun stopHuntScan()

    /** An automatic connect to [address], the wheel the hunt found. */
    fun connect(address: String, name: String?)
}

/**
 * The wheel reconnect hunt, the rules of Android's BleConnectionManager over
 * a link that only knows how to scan and connect: a wheel that drops is
 * chased by an address-filtered scan in bounded windows with growing pauses
 * ([WheelReconnect]); a disconnect the rider asked for, a virtual wheel or
 * the scan screen being open stops it; Bluetooth coming back on and the app
 * coming to the front start it again.
 *
 * The iPhone's link runs it. Not thread-safe: every call comes from one
 * thread (the main one on iOS), the timer's callbacks too.
 */
class WheelReconnectHunt(
    private val radio: HuntRadio,
    private val clock: () -> Long,
    private val timer: HuntTimer,
) {
    /** Chase the wheel when it drops: false after a deliberate disconnect and for virtual wheels. */
    var armed = false
        private set

    /** The scan screen is open: hold every automatic connect. */
    var suppressed = false
        private set

    /** The wheel to come back to. */
    var target: String? = null
        private set
    var targetName: String? = null
        private set

    /** The current connection was started by the app (a reconnect, the start-up connect), not a tap. */
    var connectIsAuto = false
        private set

    /** Scan windows since the wheel was last seen. */
    var cycle = 0
        private set

    /** When the current hunt began, 0 when none is running. */
    var huntStartMs = 0L
        private set

    var lowPower = false
        private set

    // The scan window running now; a window that is no longer this one has been stopped.
    private var window: Any? = null

    /** A scan window is open. */
    val scanning: Boolean get() = window != null

    /**
     * A connect to [address]. False when it is automatic and the scan screen
     * holds it; otherwise the hunt now watches this wheel.
     */
    fun connectRequested(address: String, name: String?, isAuto: Boolean, virtual: Boolean): Boolean {
        if (isAuto && suppressed) return false
        connectIsAuto = isAuto
        if (!isAuto) {
            cycle = 0
            huntStartMs = 0L
        }
        target = address
        targetName = name ?: targetName
        // A virtual wheel runs without the radio; there is nothing to hunt.
        armed = !virtual
        return true
    }

    /** The rider disconnected: forget the wheel and stop looking for it. */
    fun disconnectRequested() {
        armed = false
        stopScan()
        target = null
        targetName = null
    }

    /** The link dropped; [unexpected] when the wheel or the radio broke it rather than the app. */
    fun dropped(unexpected: Boolean) {
        if (!armed || !unexpected) return
        val address = target
        timer.after(WheelReconnect.DROP_DELAY_MS) { afterDrop(address) }
    }

    // Held while the rider is on the scan screen choosing a wheel; resumes once they leave it.
    private fun afterDrop(address: String?) {
        if (suppressed) {
            timer.after(WheelReconnect.SUPPRESSED_POLL_MS) { afterDrop(address) }
            return
        }
        if (WheelReconnect.huntAfterDrop(armed, target, address, radio.state)) {
            connectIsAuto = true
            startHunt(address!!)
        }
    }

    /** Bluetooth went off: any scan is dead. */
    fun bluetoothOff() = stopScan()

    /** Bluetooth came back on: return to the wheel unless the rider let it go. */
    fun bluetoothOn() {
        if (!armed || suppressed) return
        val address = target ?: return
        if (radio.state == ConnectionState.CONNECTED) return
        timer.after(WheelReconnect.BLUETOOTH_ON_SETTLE_MS) {
            if (WheelReconnect.huntAfterBluetoothOn(armed, suppressed, target, address, radio.state, radio.adapterOn)) {
                startHunt(address)
            }
        }
    }

    /** The app came to the front: a hunt idling or on low power scans at full speed again. */
    fun foreground() {
        val address = target
        if (WheelReconnect.restartOnForeground(
                armed, suppressed, hunting = huntStartMs != 0L, hasTarget = address != null,
                adapterOn = radio.adapterOn, state = radio.state, lowPower = lowPower,
            )
        ) {
            cycle = 0
            startHunt(address!!)
        }
    }

    /** The scan saw [address]. */
    fun seen(address: String) {
        if (window == null || address != target) return
        cycle = 0
        huntStartMs = 0L
        stopScan()
        radio.connect(address, targetName)
    }

    /**
     * A connect the platform left waiting during the hunt landed (iOS keeps
     * one pending beside its scan): the wheel is back, as if the scan saw it.
     */
    fun reconnected() {
        cycle = 0
        huntStartMs = 0L
        connectIsAuto = true
        stopScan()
    }

    /** The scan screen opened. True when the current automatic connection should be dropped. */
    fun suppress(): Boolean {
        suppressed = true
        stopScan()
        return connectIsAuto && radio.state != ConnectionState.DISCONNECTED
    }

    /** The scan screen closed. */
    fun resume() {
        suppressed = false
    }

    private fun startHunt(address: String) {
        stopScan()
        radio.state = ConnectionState.SCANNING
        val now = clock()
        if (cycle == 0 || huntStartMs == 0L) huntStartMs = now
        lowPower = WheelReconnect.lowPowerDue(now, huntStartMs)
        val token = Any()
        window = token
        if (!radio.startHuntScan(address, lowPower)) {
            window = null
            radio.connect(address, targetName)
            return
        }
        timer.after(WheelReconnect.SCAN_WINDOW_MS) {
            if (window !== token) return@after
            stopScan()
            if (radio.state == ConnectionState.SCANNING) radio.state = ConnectionState.DISCONNECTED
            val pause = WheelReconnect.pauseAfterWindow(cycle)
            cycle++
            timer.after(pause) {
                if (WheelReconnect.huntAgain(armed, suppressed, target, address, radio.state)) startHunt(address)
            }
        }
    }

    private fun stopScan() {
        if (window == null) return
        window = null
        radio.stopHuntScan()
    }
}
