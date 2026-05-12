package com.eried.eucplanet.audio

/**
 * Detects the "red-light balance dance" — when an EUC rider holds position by
 * rocking back and forth at very low speeds. We map that pattern to a virtual
 * throttle blip, like a motorcyclist revving at a stop.
 *
 * Algorithm:
 *  - Push every speed sample into a ring of (t, speed) pairs spanning a few seconds.
 *  - In the most recent [windowMs] window, require:
 *      * |speed| has stayed below [maxRockingKmh] the whole time (not actually moving)
 *      * speed sign crossings ≥ [minCrossings] (i.e., real back-and-forth)
 *  - On trigger, produce a transient RPM bump that decays over [decayMs].
 */
class RevDetector(
    private val windowMs: Long = 3000L,
    private val maxRockingKmh: Float = 1.5f,
    private val minCrossings: Int = 2,
    private val decayMs: Long = 1200L,
    private val bumpRpm: Float = 1500f,
    /** Minimum time between triggers so we don't spam the synth. */
    private val cooldownMs: Long = 1500L
) {
    private data class Sample(val t: Long, val speed: Float)

    private val history = ArrayDeque<Sample>(64)
    private var lastTriggerAt: Long = 0L
    private var bumpStartedAt: Long = 0L

    /** Push a fresh telemetry sample and evaluate the trigger condition. */
    fun update(nowMs: Long, speedKmh: Float) {
        history.addLast(Sample(nowMs, speedKmh))
        val cutoff = nowMs - windowMs
        while (history.isNotEmpty() && history.first().t < cutoff) history.removeFirst()

        if (nowMs - lastTriggerAt < cooldownMs) return

        // All recent samples must be within the rocking envelope
        if (history.any { kotlin.math.abs(it.speed) > maxRockingKmh }) return

        // Count sign changes of speed (treating 0 as previous sign to avoid noise spam).
        var prevSign = 0
        var crossings = 0
        for (s in history) {
            val sign = when {
                s.speed > 0.05f -> 1
                s.speed < -0.05f -> -1
                else -> 0
            }
            if (sign != 0 && prevSign != 0 && sign != prevSign) crossings++
            if (sign != 0) prevSign = sign
        }
        if (crossings >= minCrossings) {
            lastTriggerAt = nowMs
            bumpStartedAt = nowMs
        }
    }

    /** Current RPM bump value — call from the param-update tick on the main thread. */
    fun currentBump(nowMs: Long): Float {
        if (bumpStartedAt == 0L) return 0f
        val age = nowMs - bumpStartedAt
        if (age >= decayMs) return 0f
        val t = age.toFloat() / decayMs.toFloat()
        // Sharp attack (first 80ms) then exponential decay
        val attackFrac = 80f / decayMs.toFloat()
        return if (t < attackFrac) {
            bumpRpm * (t / attackFrac)
        } else {
            val k = (t - attackFrac) / (1f - attackFrac)
            bumpRpm * kotlin.math.exp(-3f * k)
        }
    }

    fun reset() {
        history.clear()
        lastTriggerAt = 0L
        bumpStartedAt = 0L
    }
}
