package com.eried.eucplanet.audio

/**
 * Platform haptic/alert feedback for alarms. iOS uses
 * `UINotificationFeedbackGenerator` + a short system sound; Android keeps its own
 * vibration/sound in `:app` (shared no-op stub).
 */
interface Haptics {
    /** Strong warning feedback for a tripped alarm. */
    fun warning()
}

/** Platform-provided haptics. */
expect fun createHaptics(): Haptics
