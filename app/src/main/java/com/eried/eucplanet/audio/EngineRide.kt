package com.eried.eucplanet.audio

/**
 * Scripted motion for the engine: the settings previews and the offline ride
 * the renderer records for listening tests. A step is either a telemetry
 * tick (speed, pwm) or, for the plain sweep, the rev band set outright.
 */
data class RideStep(
    val holdMs: Long,
    val speedKmh: Float = 0f,
    val pwmPercent: Float = 0f,
    /** True: [rpmFraction] and [load] go straight in, no speed model. */
    val direct: Boolean = false,
    val rpmFraction: Float = 0f,
    val load: Float = 0f,
)

object EngineRide {

    /** The settings-page preview for [scenario], as [EngineSoundEngine.previewProfile] plays it. */
    fun preview(scenario: String, durationMs: Long, maxSpeedRefKmh: Float): List<RideStep> = when (scenario) {
        "GEARBOX" -> gearbox(durationMs, maxSpeedRefKmh)
        "BRAKE" -> brake(durationMs)
        "DECEL" -> decel(durationMs)
        else -> sweep(durationMs)
    }

    /** Idle to mid-rev and back. */
    private fun sweep(durationMs: Long): List<RideStep> {
        val steps = 25
        val hold = (durationMs / 2) / steps
        return (0..steps).map { i ->
            val t = i.toFloat() / steps
            RideStep(hold, direct = true, rpmFraction = t, load = t * 0.7f)
        } + (0..steps).map { i ->
            val t = 1f - i.toFloat() / steps
            RideStep(hold, direct = true, rpmFraction = t, load = t * 0.3f)
        }
    }

    /** Speed sweep over the whole band so the gearbox crosses its thresholds. */
    private fun gearbox(durationMs: Long, topKmh: Float): List<RideStep> {
        val steps = 50
        return (0..steps).map { i -> RideStep(durationMs / steps, speedKmh = topKmh * (i.toFloat() / steps), pwmPercent = 55f) }
    }

    /** Steady coast at speed with the throttle closed; the engine brake rises. */
    private fun brake(durationMs: Long): List<RideStep> {
        val steps = 30
        return (0..steps).map { RideStep(durationMs / steps, speedKmh = 22f, pwmPercent = 0f) }
    }

    /** Rev up under load, then a sharp throttle drop for the decel pops. */
    private fun decel(durationMs: Long): List<RideStep> {
        val accelSteps = 18
        val accelDur = (durationMs * 0.55f).toLong()
        val tailDur = (durationMs * 0.45f).toLong()
        val tailSteps = 15
        return (0..accelSteps).map { i ->
            val t = i.toFloat() / accelSteps
            RideStep(accelDur / accelSteps, speedKmh = 10f + 25f * t, pwmPercent = 30f + 50f * t)
        } + RideStep(0L, speedKmh = 35f, pwmPercent = 0f) + (0..tailSteps).map { i ->
            RideStep(tailDur / tailSteps, speedKmh = 35f - 25f * (i.toFloat() / tailSteps), pwmPercent = 0f)
        }
    }

    /** A phase of the recorded ride, for the listener's index. */
    data class Phase(val name: String, val startMs: Long, val steps: List<RideStep>)

    /**
     * The whole ride the renderer records, at 10 ticks a second: parked idle,
     * pull-away, cruise, a hard pull, the throttle chop, coasting down on the
     * engine brake, the balance dance at a stop, idle again.
     */
    fun fullRide(): List<Phase> {
        val tick = 100L
        fun hold(seconds: Float, speed: Float, pwm: Float) =
            List((seconds * 1000 / tick).toInt()) { RideStep(tick, speed, pwm) }
        fun ramp(seconds: Float, s0: Float, s1: Float, p0: Float, p1: Float): List<RideStep> {
            val n = (seconds * 1000 / tick).toInt()
            return List(n) { i -> val t = i.toFloat() / (n - 1).coerceAtLeast(1); RideStep(tick, s0 + (s1 - s0) * t, p0 + (p1 - p0) * t) }
        }
        val phases = mutableListOf<Phase>()
        var at = 0L
        fun add(name: String, steps: List<RideStep>) { phases += Phase(name, at, steps); at += steps.sumOf { it.holdMs } }
        add("idle", hold(3f, 0f, 0f))
        add("pull-away 0-25", ramp(4f, 0f, 25f, 20f, 60f))
        add("cruise 25", hold(3f, 25f, 30f))
        add("hard pull 25-55", ramp(4f, 25f, 55f, 85f, 85f))
        add("throttle chop", hold(0.5f, 55f, 0f))
        add("coast 55-5", ramp(4.5f, 55f, 5f, 0f, 0f))
        add("balance dance", List(20) { i -> RideStep(tick, if (i % 4 < 2) 0.8f else -0.8f, 0f) })
        add("idle", hold(3f, 0f, 0f))
        return phases
    }
}
