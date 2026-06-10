package com.eried.eucplanet.audio

/**
 * Android keeps its own Vibrator/alarm-sound wiring (which has the Context) in
 * `:app`; shared no-op stub so the module compiles for Android.
 */
private class NoopHaptics : Haptics {
    override fun warning() {}
}

actual fun createHaptics(): Haptics = NoopHaptics()
