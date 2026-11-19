package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.data.model.EngoHudSettings
import com.eried.eucplanet.data.repository.MetricSample
import kotlin.math.sin

/**
 * Sample numbers for the rider's own ENGO page, for the settings preview when
 * no wheel is riding and for the test renders. The layout, the picked readings,
 * the statistics and the units are the rider's; only the inputs are made up:
 * a minute of history per metric that wanders like a ride does.
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

    /** A minute and a half of 1 Hz samples ending at [nowMs]; the last one is the live value. */
    fun history(key: String, nowMs: Long): List<MetricSample> {
        val v = VALUES[key] ?: return emptyList()
        val phase = key.hashCode() % 7
        val swing = when (key) {
            "BATTERY", "TRIP", "PHONE_BATTERY", "RANGE_ESTIMATE" -> 0.01f
            "TEMPERATURE", "MOTOR_TEMP", "CONTROLLER_TEMP", "BATTERY_TEMP" -> 0.06f
            else -> 0.35f
        }
        return (90 downTo 0).map { ago ->
            val t = ago.toFloat()
            val wave = sin(t / 9f + phase) * 0.6f + sin(t / 3.3f + phase * 2) * 0.4f
            MetricSample(nowMs - ago * 1000L, v + wave * swing * v)
        }
    }

    fun current(key: String, nowMs: Long): Float? = history(key, nowMs).lastOrNull()?.value

    /** The rider's page on sample numbers; [navigating] adds a left turn in 120 m. */
    fun page(
        engo: EngoHudSettings,
        units: EngoMetrics.Units,
        orangePct: Int,
        redPct: Int,
        navigating: Boolean,
        nowMs: Long,
        speedKmh: Float = VALUES.getValue("SPEED"),
    ): EngoPage = EngoPageBuilder.build(
        engo = engo,
        units = units,
        orangePct = orangePct,
        redPct = redPct,
        connected = true,
        current = { if (it == "SPEED") speedKmh else current(it, nowMs) },
        // The speed's minute follows the speed shown, so TOP is never below it.
        history = { k ->
            val h = history(k, nowMs)
            if (k == "SPEED") h.map { it.copy(value = it.value * speedKmh / VALUES.getValue("SPEED")) } else h
        },
        nav = if (navigating) EngoNav(EngoManeuver.LEFT, if (units.distance == "mi") "400 FT" else "120 M") else null,
        nowMs = nowMs,
    )
}
