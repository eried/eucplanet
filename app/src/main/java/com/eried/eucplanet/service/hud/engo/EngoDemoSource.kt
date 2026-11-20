package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.data.model.EngoHudSettings
import com.eried.eucplanet.data.repository.MetricSample
import kotlin.math.sin

/**
 * Sample numbers for the rider's own ENGO page, for the settings preview when
 * no wheel is riding and for the test renders. The layout, the picked
 * elements, their statistics, styles and graph windows, the font and the
 * units are the rider's; only the inputs are made up: each metric wanders the
 * way a ride does, slow swells with a quicker ripple on top.
 */
object EngoDemoSource {

    /** A plausible live value per metric, in stored units (km/h, °C, kPa, m). */
    private val VALUES = mapOf(
        "SPEED" to 42f, "GPS_SPEED" to 41.5f, "LOAD" to 58f, "BATTERY" to 76f, "VOLTAGE" to 96.4f,
        "CURRENT" to 18.5f, "PHASE_CURRENT" to 112f, "MOTOR_POWER" to 1850f, "BATTERY_POWER" to 1780f,
        "TEMPERATURE" to 41f, "MOTOR_TEMP" to 58f, "CONTROLLER_TEMP" to 47f, "BATTERY_TEMP" to 33f,
        "TRIP" to 23.4f, "RANGE_ESTIMATE" to 64f, "WH_PER_KM" to 21f, "TORQUE" to 38f,
        "PITCH" to 2.1f, "ROLL" to -1.5f, "TIRE_PRESSURE" to 220f, "GPS_ALTITUDE" to 112f,
        "PHONE_BATTERY" to 64f,
    )

    private const val HISTORY_SEC = 300

    /** The metric [ago] seconds before now: its live value [ago] = 0. */
    private fun valueAt(key: String, base: Float, ago: Float): Float {
        val phase = key.hashCode() % 7
        val swing = when (key) {
            "BATTERY", "TRIP", "PHONE_BATTERY", "RANGE_ESTIMATE" -> 0.01f
            "TEMPERATURE", "MOTOR_TEMP", "CONTROLLER_TEMP", "BATTERY_TEMP" -> 0.06f
            else -> 0.35f
        }
        val wave = sin(ago / 9f + phase) * 0.55f + sin(ago / 3.3f + phase * 2) * 0.33f +
            sin(ago / 0.8f + phase * 3) * 0.12f
        val now = sin(phase.toFloat()) * 0.55f + sin(phase * 2f) * 0.33f + sin(phase * 3f) * 0.12f
        return base + (wave - now) * swing * base
    }

    /** Five minutes of 1 Hz samples ending at [nowMs]; the last one is the live value. */
    fun history(key: String, nowMs: Long): List<MetricSample> {
        val v = VALUES[key] ?: return emptyList()
        return (HISTORY_SEC downTo 0).map { ago -> MetricSample(nowMs - ago * 1000L, valueAt(key, v, ago.toFloat())) }
    }

    /** The last [windowMs] at ten samples a second, as the glasses' own fast buffer keeps them. */
    fun recent(key: String, windowMs: Long, nowMs: Long): List<MetricSample> {
        val v = VALUES[key] ?: return emptyList()
        val stepMs = maxOf(100L, windowMs / 300)
        return (windowMs / stepMs downTo 0).map { k ->
            val ago = k * stepMs
            MetricSample(nowMs - ago, valueAt(key, v, ago / 1000f))
        }
    }

    fun current(key: String): Float? = VALUES[key]

    /** The rider's page on sample numbers; [navigating] adds a left turn in 120 m. */
    fun page(
        engo: EngoHudSettings,
        units: EngoMetrics.Units,
        orangePct: Int,
        redPct: Int,
        navigating: Boolean,
        nowMs: Long,
        speedKmh: Float = VALUES.getValue("SPEED"),
        dashboardWindowSec: Int = 300,
    ): EngoPage {
        // The speed's history follows the speed shown, so TOP is never below it.
        val k = speedKmh / VALUES.getValue("SPEED")
        fun scaled(key: String, s: List<MetricSample>) =
            if (key == "SPEED") s.map { it.copy(value = it.value * k) } else s
        return EngoPageBuilder.build(
            engo = engo,
            units = units,
            orangePct = orangePct,
            redPct = redPct,
            connected = true,
            current = { if (it == "SPEED") speedKmh else current(it) },
            history = { key -> scaled(key, history(key, nowMs)) },
            recent = { key, w -> scaled(key, recent(key, w, nowMs)) },
            nav = if (navigating) EngoNav(EngoManeuver.LEFT, if (units.distance == "mi") "400 FT" else "120 M") else null,
            nowMs = nowMs,
            dashboardWindowSec = dashboardWindowSec,
        )
    }
}
