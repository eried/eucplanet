package com.eried.eucplanet.audio

import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

/** iOS haptics: a warning notification haptic + a short system alert sound. */
private class IosHaptics : Haptics {
    private val generator = UINotificationFeedbackGenerator()

    override fun warning() {
        generator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
        AudioServicesPlaySystemSound(1005u) // short system alert tone
    }
}

actual fun createHaptics(): Haptics = IosHaptics()
