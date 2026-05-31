package com.eried.eucplanet.hud.overlay

import androidx.compose.ui.geometry.Offset
import com.eried.eucplanet.hud.protocol.HudState

/**
 * HUD-side mirror of the phone's StudioElementData. Same shape so the
 * ported renderer code stays unchanged. Fields the HUD doesn't have
 * (replay history, camera hub, custom marker photo) default to empty /
 * stub values; renderers that fall back gracefully on those will look
 * identical to their phone selves for what we DO send.
 */
data class StudioElementData(
    val wheelData: WheelData,
    val wheelName: String,
    val connected: Boolean,
    /** Replay scrub history. Empty on the HUD; renderers that need a
     *  rolling history maintain their own local buffer instead. */
    val history: List<StudioSample> = emptyList(),
    val speedUnit: String,
    val distanceUnit: String,
    val tempUnit: String,
    val clockTimeMs: Long = System.currentTimeMillis(),
    val stopwatchMs: Long = 0L,
    val liveGForceTrail: List<Offset> = emptyList(),
    /** GPS lat/lng for the MAP element. Doubles, 0 = no fix. */
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    /** Heading degrees (0 = north, +clockwise). NaN when no bearing. */
    val gpsHeadingDeg: Float = Float.NaN,
    /** Rider's accent colour as ARGB. */
    val accentArgb: Long = 0xFF00C853L
) {
    companion object {
        /** Build a StudioElementData from a [HudState] frame. The phone
         *  populates HudState with whatever is currently flowing through
         *  the wheel BLE link + GPS, so this is just field-by-field. */
        fun from(hud: HudState): StudioElementData {
            val wd = WheelData(
                speed = hud.speedKmh,
                voltage = hud.voltage,
                current = hud.current,
                batteryPercent = hud.batteryPercent,
                pwm = hud.pwm,
                torque = hud.torque,
                maxTemperature = hud.temperatureC,
                tripDistance = hud.tripKm,
                pitchAngle = hud.wheelPitchDeg,
                rollAngle = hud.wheelRollDeg,
                latitude = hud.latitude,
                longitude = hud.longitude,
                lightOn = hud.lightOn,
                timestamp = hud.timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            return StudioElementData(
                wheelData = wd,
                wheelName = hud.wheelName,
                connected = hud.connected,
                speedUnit = hud.unitSpeed,
                distanceUnit = hud.unitDistance,
                tempUnit = hud.unitTemp,
                latitude = hud.latitude,
                longitude = hud.longitude,
                gpsHeadingDeg = hud.gpsHeadingDeg,
                accentArgb = parseArgbStringToLong(hud.accentArgb)
            )
        }
    }
}

/** Sample on the StudioSample timeline. The HUD doesn't replay, so this
 *  is mostly a placeholder type to keep the renderer code's signatures
 *  unchanged. */
data class StudioSample(val timeMs: Long, val data: WheelData)

private fun parseArgbStringToLong(hex: String): Long = try {
    val v = hex.removePrefix("#")
    when (v.length) {
        6 -> (0xFF000000L or v.toLong(16))
        8 -> v.toLong(16)
        else -> 0xFF00C853L
    }
} catch (_: Throwable) {
    0xFF00C853L
}
