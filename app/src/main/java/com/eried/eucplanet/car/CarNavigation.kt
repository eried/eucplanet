package com.eried.eucplanet.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.car.app.CarContext
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.core.graphics.drawable.IconCompat
import com.eried.eucplanet.data.model.ArrowDir
import com.eried.eucplanet.data.model.NavState
import com.eried.eucplanet.data.model.arrowAngleDeg
import java.util.TimeZone

/**
 * The car side of a running route: the phone navigator's cue, as Android
 * Auto's own turn card and arrival line. Everything comes from [NavState], the
 * same frame the phone's top bar and turn card draw, so the car never says
 * something the phone does not.
 */
internal object CarNavigation {

    /** The navigation template: turn card on top, arrival at the bottom, the
     *  map surface (drawn by [CarRideContent]) underneath. */
    fun template(car: CarContext, state: NavState, imperial: Boolean, strip: ActionStrip): NavigationTemplate {
        val b = NavigationTemplate.Builder().setActionStrip(strip)
        val step = step(car, state)
        val cueM = state.cueDistanceM.takeUnless { it.isNaN() } ?: state.remainingM
        if (step != null && !cueM.isNaN()) {
            b.setNavigationInfo(RoutingInfo.Builder().setCurrentStep(step, distance(cueM, imperial)).build())
        } else {
            // Waiting for a heading, arrived, or no distance yet: the engine's
            // own line, the way the phone shows it.
            b.setNavigationInfo(
                MessageInfo.Builder(state.primaryText.ifBlank { " " })
                    .setImage(icon(car, state))
                    .build()
            )
        }
        estimate(state, imperial)?.let { b.setDestinationTravelEstimate(it) }
        return b.build()
    }

    /** The same cue for the car's cluster and heads-up display. */
    fun trip(car: CarContext, state: NavState, imperial: Boolean): Trip {
        val t = Trip.Builder()
        val est = estimate(state, imperial)
        val step = step(car, state)
        if (step != null && est != null) t.addStep(step, est)
        state.nextStreet.takeIf { it.isNotBlank() }?.let { t.setCurrentRoad(it) }
        if (state.waiting) t.setLoading(true)
        return t.build()
    }

    private fun step(car: CarContext, state: NavState): Step? {
        if (state.arrived || state.waiting || state.offRoute) return null
        val cue = listOf(state.primaryText, state.nextStreet).filter { it.isNotBlank() }.joinToString(" · ")
        if (cue.isBlank()) return null
        return Step.Builder(cue)
            .setManeuver(Maneuver.Builder(maneuverType(state)).setIcon(icon(car, state)).build())
            .apply { state.nextStreet.takeIf { it.isNotBlank() }?.let { setRoad(it) } }
            .build()
    }

    /** Arrival and distance left, or null while the engine has neither. */
    private fun estimate(state: NavState, imperial: Boolean): TravelEstimate? {
        if (state.remainingM.isNaN() || state.etaEpochMs <= 0L) return null
        val secs = ((state.etaEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        return TravelEstimate.Builder(
            distance(state.remainingM, imperial),
            DateTimeWithZone.create(state.etaEpochMs, TimeZone.getDefault()),
        ).setRemainingTimeSeconds(secs).build()
    }

    /** The rider's unit, rounded the way a turn card reads it. */
    private fun distance(m: Double, imperial: Boolean): Distance = if (imperial) {
        val mi = m / 1609.344
        if (mi < 0.1) Distance.create(m * 3.28084, Distance.UNIT_FEET)
        else Distance.create(mi, Distance.UNIT_MILES)
    } else {
        if (m < 1000) Distance.create(m, Distance.UNIT_METERS)
        else Distance.create(m / 1000.0, Distance.UNIT_KILOMETERS)
    }

    private fun maneuverType(state: NavState): Int = when {
        state.arrived -> Maneuver.TYPE_DESTINATION
        state.straightLine -> Maneuver.TYPE_STRAIGHT
        else -> when (state.arrow) {
            ArrowDir.STRAIGHT -> Maneuver.TYPE_STRAIGHT
            ArrowDir.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            ArrowDir.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            ArrowDir.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            ArrowDir.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            ArrowDir.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            ArrowDir.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            ArrowDir.REVERSE -> Maneuver.TYPE_U_TURN_LEFT
        }
    }

    /**
     * The phone's arrow, at the phone's angle, as a bitmap. Treasure hunt
     * points at any bearing, which no fixed maneuver glyph can, so the icon is
     * drawn rather than picked. White: the host tints the turn card.
     */
    private fun icon(car: CarContext, state: NavState): CarIcon {
        val px = (64 * car.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val s = px.toFloat()
        if (state.arrived) {
            c.drawCircle(s / 2f, s / 2f, s * 0.3f, p)
        } else {
            c.rotate(state.arrowAngleDeg(), s / 2f, s / 2f)
            val arrow = Path().apply {
                moveTo(s * 0.5f, s * 0.1f)
                lineTo(s * 0.82f, s * 0.86f)
                lineTo(s * 0.5f, s * 0.68f)
                lineTo(s * 0.18f, s * 0.86f)
                close()
            }
            c.drawPath(arrow, p)
        }
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }
}
