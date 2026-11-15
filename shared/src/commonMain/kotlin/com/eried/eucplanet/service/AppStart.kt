package com.eried.eucplanet.service

/** How the process came up, which decides a little of what the start does. */
enum class LaunchKind {
    /** The rider opened the app: a screen follows at once. */
    FOREGROUND,

    /**
     * The system relaunched the app in the background because a Bluetooth
     * device it was linked to spoke (iOS state restoration): no screen, and
     * a ride may be going on.
     */
    BLUETOOTH_RELAUNCH,
}

/**
 * Runs the process-wide start (the app graph, the wheel link, the ride
 * session, the recorder) once, whoever asks first: the app's launch, with or
 * without a screen, or the first screen. Later asks do nothing and say so,
 * so a screen that appears after a background start attaches to what is
 * already running instead of starting a second copy. Main thread only.
 */
class StartOnce(private val start: (LaunchKind) -> Unit) {
    /** The launch that ran the start, null before it ran. */
    var kind: LaunchKind? = null
        private set

    val started: Boolean get() = kind != null

    /** Runs the start for [kind] the first time; true when this call ran it. */
    fun start(kind: LaunchKind): Boolean {
        if (this.kind != null) return false
        this.kind = kind
        start.invoke(kind)
        return true
    }
}

/** A peripheral iOS handed back on a relaunch, in plain terms. */
data class RestoredPeripheral(val id: String, val name: String?, val connected: Boolean)

/**
 * Which wheel to pick back up from what iOS restored to the wheel's
 * Bluetooth central. That central only ever links one wheel, so there is
 * normally one; with more, the rider's last wheel, then a live link over a
 * connect still waiting, then the first. Null when nothing was restored.
 */
object WheelRestore {
    fun pick(restored: List<RestoredPeripheral>, lastAddress: String? = null): RestoredPeripheral? =
        restored.firstOrNull { lastAddress != null && it.id.equals(lastAddress, ignoreCase = true) }
            ?: restored.firstOrNull { it.connected }
            ?: restored.firstOrNull()
}
