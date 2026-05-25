package com.eried.eucplanet.audio

/**
 * Snapshot of live engine state, produced by [EngineSoundEngine] and consumed by
 * the audio thread on every buffer fill. All fields are smoothed/derived, the
 * raw (speed, pwm) inputs never reach the synth directly so jittery telemetry
 * doesn't make the engine warble.
 *
 * Immutable / value-type by intent; copy a new one on each update.
 */
data class EngineParams(
    /** Smoothed engine RPM (post low-pass). */
    val rpm: Float = 0f,
    /** Smoothed load 0..1 (PWM normalized, with floor for engine compression sound at idle). */
    val load: Float = 0f,
    /** Decel intensity 0..1, rises when PWM falls sharply; drives backfire pops. */
    val decelAmount: Float = 0f,
    /** Idle envelope 0..1, fades the whole signal out after parked timeout. */
    val idleAmount: Float = 1f,
    /** Master output gain 0..1 (master volume × duck factor). */
    val masterGain: Float = 1f,
    /** Number of decel pop events queued for this buffer (0+, typically 0 or 1). */
    val pendingPops: Int = 0,
    /** Transient RPM bump from rev-up detection, added on top of [rpm] in the synth. */
    val revBump: Float = 0f,
    /**
     * Engine brake intensity 0..1, rises during sustained regen/decel, decays
     * back to 0 under accel/cruise. Drives the high-frequency overrun whine.
     */
    val engineBrakeAmount: Float = 0f,
    /** Active profile. */
    val profile: EngineProfile = EngineProfile.byKey("FOUR_STROKE_SINGLE"),
    /** Muffler cutoff scale 0..1 (1 = open pipes, 0 = closed muffler). */
    val mufflerOpenness: Float = 0.6f
) {
    companion object {
        /** Silent / off state. */
        val SILENT = EngineParams(rpm = 0f, masterGain = 0f, idleAmount = 0f)
    }
}
