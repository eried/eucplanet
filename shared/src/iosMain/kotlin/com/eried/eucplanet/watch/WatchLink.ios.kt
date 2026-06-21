package com.eried.eucplanet.watch

/**
 * iOS side of the phone↔watch link. Pure Kotlin glue — the actual
 * `WCSession` plumbing lives in Swift (`iosApp/WatchSessionManager.swift`),
 * which wires itself to this object at launch:
 *
 *  - sets [nativeSink] so every [publish] is forwarded to the Apple Watch, and
 *  - calls [deliverControl] when the watch sends a button intent, which this
 *    object routes to the handler the Compose `App()` registered.
 *
 * Kept as a Kotlin `object` so Swift sees a single shared instance
 * (`WatchLink.shared`) and the Compose side can call it without a handle.
 */
actual object WatchLink {
    /** Set by Swift at launch to forward snapshots into `WCSession`. */
    var nativeSink: ((WatchState) -> Unit)? = null

    private var controlHandler: ((String) -> Unit)? = null

    actual fun publish(state: WatchState) {
        nativeSink?.invoke(state)
    }

    actual fun setControlHandler(handler: (String) -> Unit) {
        controlHandler = handler
    }

    /** Called from Swift when the watch sends a [WatchControl] intent. */
    fun deliverControl(action: String) {
        controlHandler?.invoke(action)
    }
}
