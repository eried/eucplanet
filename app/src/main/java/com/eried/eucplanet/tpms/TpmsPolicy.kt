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

    /**
     * How long the active sensor keeps the badge after its last message.
     *
     * Long enough that a cap pausing between broadcasts does not lose the
     * badge, short enough that a sensor which actually stopped hands over
     * before the rider notices a frozen number. These report on change, so
     * quiet is normal and a minute of it means nothing.
     */
    const val ACTIVE_HOLD_MS = 60 * 1000L

    /** One source that could be the active one. */
    data class Candidate(
        /** Null for the wheel's own relayed reading. */
        val address: String?,
        val source: TpmsSource,
        val atMs: Long,
    )

    /**
     * Which sensor should be shown as active, or null when nothing is.
     *
     * [currentActive] is the address that holds the badge right now (null for
     * the wheel), and it is what makes the choice sticky rather than a race
     * between whichever packet landed last.
     */
    fun pickActive(
        candidates: List<Candidate>,
        nowMs: Long,
        currentActive: String?,
        holdMs: Long = ACTIVE_HOLD_MS,
        staleAfterMs: Long = STALE_AFTER_MS,
    ): Candidate? {
        val live = candidates.filter { nowMs - it.atMs < staleAfterMs }
        if (live.isEmpty()) return null

        val externals = live.filter { it.source == TpmsSource.PAIRED }
        if (externals.isEmpty()) {
            // Rule 2: the wheel only speaks for the tyre when no cap can.
            return live.firstOrNull { it.source == TpmsSource.WHEEL }
        }

        // Rule 3: the incumbent keeps it while it is still talking.
        val incumbent = externals.firstOrNull { it.address == currentActive }
        if (incumbent != null && nowMs - incumbent.atMs < holdMs) return incumbent

        // Otherwise the one that spoke most recently, ties broken by the order
        // the rider added them so the choice is stable rather than arbitrary.
        return externals.maxWithOrNull(
            compareBy<Candidate> { it.atMs }.thenByDescending { candidates.indexOf(it) }
        )
    }

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
    /**
     * A reading, or null when the number means "no sensor" rather than a
     * pressure.
     *
     * Zero means different things depending on who said it. A wheel leaves the
     * field at zero when nothing is bound to it, so zero from a wheel is an
     * absence. A cap screwed onto a valve reports the pressure it measures,
     * and a flat tyre measures zero: the rider's own sensor read exactly 0 kPa
     * with the gauge at 0 bar, which is how the format was decoded in the
     * first place.
     *
     * Dropping it made a flat tyre look like a broken sensor, which is the
     * opposite of the one moment a tyre sensor exists for.
     */
    fun readingOf(kpa: Float, source: TpmsSource, atMs: Long): TpmsReading? = when {
        kpa > 0f -> TpmsReading(kpa, source, atMs)
        source == TpmsSource.PAIRED -> TpmsReading(0f, source, atMs)
        else -> null
    }
}
