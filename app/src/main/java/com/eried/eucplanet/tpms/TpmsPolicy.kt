package com.eried.eucplanet.tpms

/**
 * Where a tire-pressure reading came from.
 *
 * The wheel relaying a sensor it owns and a sensor the rider paired directly
 * are different things, and a rider who paired one wants to see that one. The
 * settings section has always said so: "An extra sensor replaces the wheel's
 * built-in one, if any."
 */
enum class TpmsSource {
    /** Relayed by the wheel from a sensor bound to it (InMotion P6 today). */
    WHEEL,

    /** A sensor the rider paired with the phone directly. */
    PAIRED,
}

/** One pressure reading, in the kPa every surface already stores. */
data class TpmsReading(
    val kpa: Float,
    val source: TpmsSource,
    /** When it arrived, for staleness. */
    val atMs: Long,
)

/**
 * Which reading to believe, and for how long.
 *
 * Pure so the rules can be argued with in a unit test rather than on a wheel
 * in a car park. It answers two questions that a second source creates and a
 * single source never had:
 *
 *  - **Which one.** A paired sensor wins. It is the one the rider went and
 *    bought, and if both are present the wheel's is the one they are
 *    replacing.
 *  - **For how long.** A TPMS sensor is a battery in a valve cap: it reports
 *    on its own schedule and goes quiet when parked, and it stops for good
 *    when the battery dies. A reading that stops arriving has to stop being
 *    shown, or a rider reads a pressure from last week as if it were now.
 */
object TpmsPolicy {

    /**
     * How long a reading stays believable with nothing new behind it.
     *
     * Sensors report every few tens of seconds while moving and slow right
     * down when parked, so a window this wide survives a normal quiet spell
     * and still drops a sensor whose battery died overnight. It is not a
     * setting: a rider cannot know their sensor's duty cycle, and a wrong
     * value here is worse than no value.
     */
    const val STALE_AFTER_MS = 10 * 60 * 1000L

    /** True when [reading] is too old to show. */
    fun isStale(reading: TpmsReading?, nowMs: Long, staleAfterMs: Long = STALE_AFTER_MS): Boolean =
        reading == null || nowMs - reading.atMs >= staleAfterMs

    /**
     * The reading to publish, or null when nothing fresh has anything to say.
     *
     * A stale paired sensor does NOT fall back to the wheel's. The rider
     * replaced that one deliberately, and silently swapping back would show a
     * different tyre's pressure under the same label with nothing to say it
     * changed. Silence is the honest answer, and the settings row says which
     * sensor is quiet.
     */
    fun pick(
        paired: TpmsReading?,
        wheel: TpmsReading?,
        nowMs: Long,
        staleAfterMs: Long = STALE_AFTER_MS,
    ): TpmsReading? = when {
        paired != null && !isStale(paired, nowMs, staleAfterMs) -> paired
        paired != null -> null
        !isStale(wheel, nowMs, staleAfterMs) -> wheel
        else -> null
    }

    /**
     * 0 kPa is "no sensor", not a flat tyre.
     *
     * Every family that does not report pressure leaves the field at zero, so
     * a reading of zero has to be dropped rather than published. The alarm
     * engine makes the same call for the same reason: a low-pressure rule on a
     * wheel without TPMS would otherwise fire on the first frame and never
     * stop.
     */
    fun readingOf(kpa: Float, source: TpmsSource, atMs: Long): TpmsReading? =
        if (kpa > 0f) TpmsReading(kpa, source, atMs) else null
}
