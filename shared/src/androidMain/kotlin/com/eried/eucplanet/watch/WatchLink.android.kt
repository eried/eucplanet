package com.eried.eucplanet.watch

/**
 * Android no-op. The Android app drives Wear OS through its own `WearBridge`
 * (Data Layer publisher) in the `:app` module, so the shared module must stay
 * silent here to avoid double-publishing. Exists only to satisfy the
 * `expect` declaration when the shared module compiles for Android.
 */
actual object WatchLink {
    actual fun publish(state: WatchState) {}
    actual fun setControlHandler(handler: (String) -> Unit) {}
}
