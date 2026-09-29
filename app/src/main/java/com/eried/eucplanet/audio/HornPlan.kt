package com.eried.eucplanet.audio

import com.eried.eucplanet.data.model.HornSettings

/**
 * What one horn press does, decided without touching audio or Bluetooth so
 * every mode and fallback is testable. The rule that shapes it: a horn press
 * is never silent. If the phone cannot play the chosen sound (no file, or
 * "only with headphones" and none are connected), the wheel's horn sounds
 * instead.
 */
object HornPlan {

    data class Plan(
        /** Send the wheel's own horn command. */
        val wheel: Boolean,
        /** Play the rider's sound on the phone. */
        val phoneSound: Boolean,
    )

    fun decide(mode: String, soundReady: Boolean, headphonesOnly: Boolean, externalOutput: Boolean): Plan {
        val phoneOk = soundReady && (!headphonesOnly || externalOutput)
        return when (mode) {
            HornSettings.MODE_SOUND -> if (phoneOk) Plan(wheel = false, phoneSound = true) else Plan(wheel = true, phoneSound = false)
            HornSettings.MODE_BOTH -> Plan(wheel = true, phoneSound = phoneOk)
            else -> Plan(wheel = true, phoneSound = false)
        }
    }
}
