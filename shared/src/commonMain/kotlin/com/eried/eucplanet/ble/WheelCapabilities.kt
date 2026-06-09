package com.eried.eucplanet.ble

/**
 * What features a given wheel exposes via BLE. The UI consults this to gray out
 * action buttons and hide settings sections that don't apply to the connected
 * wheel. New capabilities should only be added when at least one wheel supports
 * them; empty defaults keep new adapters honest about declaring support.
 */
data class WheelCapabilities(
    val hasHorn: Boolean = false,
    val hasLight: Boolean = false,
    val hasLock: Boolean = false,
    val hasMaxSpeed: Boolean = false,
    val hasAlarmSpeed: Boolean = false,
    val hasVolume: Boolean = false,
    val hasDRL: Boolean = false,
    val needsAuthForLock: Boolean = false
) {
    companion object {
        /** V11/V12/V13/V14: full feature set, lock requires password auth. */
        val INMOTION_V2 = WheelCapabilities(
            hasHorn = true,
            hasLight = true,
            hasLock = true,
            hasMaxSpeed = true,
            hasAlarmSpeed = true,
            hasVolume = true,
            hasDRL = true,
            needsAuthForLock = true
        )

        /**
         * InMotion V1 family (V5 / V8 / V10 / L6 / Glide 3 / R-series).
         * Horn and headlight are universal. Volume + DRL are firmware-
         * dependent (V8F / V8S / V10 family / Glide 3 only); the adapter
         * narrows them per detected model. No remote lock command; lock
         * state is observable in work mode but not commandable. Alarm
         * speed is not user-configurable on V1; alarms are firmware
         * tilt-back triggers reported via the async alert frame.
         */
        val INMOTION_V1 = WheelCapabilities(
            hasHorn = true,
            hasLight = true,
            hasLock = false,
            hasMaxSpeed = true,
            hasAlarmSpeed = false,
            hasVolume = true,
            hasDRL = true,
            needsAuthForLock = false
        )

        /** KingSong KS-* wheels: no software lock, no volume control. */
        val KINGSONG = WheelCapabilities(
            hasHorn = true,
            hasLight = true,
            hasLock = false,
            hasMaxSpeed = true,
            hasAlarmSpeed = true,
            hasVolume = false,
            hasDRL = false,
            needsAuthForLock = false
        )

        /**
         * Begode/Gotway: no software lock (dismount only), no native
         * volume control. Light is a 3-state (off/dim/full); the adapter
         * collapses dim to off for the on/off toggle.
         */
        val BEGODE = WheelCapabilities(
            hasHorn = true,
            hasLight = true,
            hasLock = false,
            hasMaxSpeed = true,
            hasAlarmSpeed = true,
            hasVolume = false,
            hasDRL = false,
            needsAuthForLock = false
        )

        /**
         * Veteran: minimal control surface. Telemetry is rich (cells,
         * BMS) but writes are limited to horn, light on/off and a few
         * threshold setters.
         */
        val VETERAN = WheelCapabilities(
            hasHorn = true,
            hasLight = true,
            hasLock = false,
            hasMaxSpeed = true,    // LdAp 17-byte frame (decoded from a captured LeaperKim session)
            hasAlarmSpeed = true,  // LkAp 17-byte frame (same source)
            hasVolume = false,
            hasDRL = false,
            needsAuthForLock = false
        )

        /**
         * Ninebot Z (Z6 / Z10 / new-stack E+ / Mini Plus): full settings
         * surface. No documented horn opcode but lock, speed limit, three
         * alarm slots, LED, volume, and DRL via DriveFlags bit 0 are all
         * writable. The wheel does NOT enforce a PIN for lock; the
         * encrypted handshake is the security gate, so [needsAuthForLock]
         * stays false here. Spec section 19.
         */
        val NINEBOT_Z = WheelCapabilities(
            hasHorn = false,
            hasLight = true,
            hasLock = true,
            hasMaxSpeed = true,
            hasAlarmSpeed = true,
            hasVolume = true,
            hasDRL = true,
            needsAuthForLock = false
        )

        /**
         * Ninebot legacy (One E / E+ / S2 / Mini / Mini Pro): read-only
         * telemetry over BLE. The legacy stack does not expose lock,
         * alarms, lights, volume, or LED through any documented parameter
         * (spec section 16). Settings changes require the official Ninebot
         * app over a side channel we don't cover.
         */
        val NINEBOT_LEGACY = WheelCapabilities(
            hasHorn = false,
            hasLight = false,
            hasLock = false,
            hasMaxSpeed = false,
            hasAlarmSpeed = false,
            hasVolume = false,
            hasDRL = false,
            needsAuthForLock = false
        )
    }
}
