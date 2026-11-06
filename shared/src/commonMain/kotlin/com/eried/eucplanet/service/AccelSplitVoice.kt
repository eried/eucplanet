package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AccelSplitSettings
import com.eried.eucplanet.platform.Locale
import com.eried.eucplanet.platform.format
import com.eried.eucplanet.resources.*
import kotlin.math.abs
import org.jetbrains.compose.resources.StringResource

/**
 * Builds the spoken sentence for an acceleration split. Shared by [WheelService]
 * (live announcements) and the settings preview button so the two never drift.
 * Split times are voiced to hundredths (matching RaceBox, e.g. "1.21 seconds");
 * comparison deltas to tenths ("0.2 seconds faster than previous").
 *
 * [text] reads a string in the rider's language with its format arguments
 * (Context.getString on Android).
 */
object AccelSplitVoice {

    // Below this, a comparison is not worth voicing (rounds to 0.0s at tenths).
    private const val MIN_DELTA = 0.05

    private fun fmtSeconds(sec: Double): String = String.format(Locale.US, "%.2f", sec)

    private fun fmtDelta(sec: Double): String = String.format(Locale.US, "%.1f", abs(sec))

    fun splitText(text: (StringResource, List<Any>) -> String, s: AccelSplitTracker.Split, cfg: AccelSplitSettings): String {
        val sb = StringBuilder()
        // Braking (descending) splits get a spoken lead-in so the rider can tell
        // them apart from acceleration splits by ear, since the numbers alone
        // sound alike (e.g. "braking, 40 to 30" vs "20 to 30").
        if (s.toSpeed < s.fromSpeed) {
            // Android trims the string's trailing space; put it back.
            sb.append(text(Res.string.accel_split_decel_prefix, emptyList()).trimEnd()).append(' ')
        }
        sb.append(
            text(Res.string.accel_split_time_fmt, listOf(s.fromSpeed, s.toSpeed, fmtSeconds(s.seconds)))
        )
        // Prefer the previous-run comparison; the best comparison only speaks
        // when previous did not, so a single split never reads two deltas.
        val prev = s.deltaVsPrevious
        val best = s.deltaVsBest
        var spokeComparison = false
        if (cfg.compareToPrevious && prev != null && abs(prev) >= MIN_DELTA) {
            val id = if (prev < 0) Res.string.accel_split_faster_prev else Res.string.accel_split_slower_prev
            sb.append(text(id, listOf(fmtDelta(prev))))
            spokeComparison = true
        }
        if (cfg.compareToBest) {
            if (s.isNewBest) {
                sb.append(text(Res.string.accel_split_best_yet, emptyList()))
            } else if (!spokeComparison && best != null && abs(best) >= MIN_DELTA) {
                val id = if (best < 0) Res.string.accel_split_faster_best else Res.string.accel_split_slower_best
                sb.append(text(id, listOf(fmtDelta(best))))
            }
        }
        return sb.toString()
    }

    /** Representative sentence(s) for the settings preview button, honoring the
     *  rider's comparison toggles and the chosen direction: an acceleration
     *  example ("20 to 30"), a braking example ("30 to 20"), or both back to
     *  back so the rider hears exactly what that mode will announce. */
    fun previewText(text: (StringResource, List<Any>) -> String, cfg: AccelSplitSettings): String {
        val lo = cfg.minSpeed
        val hi = cfg.minSpeed + cfg.increment
        val accel = splitText(text, demo(lo, hi, 1.21), cfg)
        val brake = splitText(text, demo(hi, lo, 1.30), cfg)
        return when (cfg.direction) {
            "BRAKE" -> brake
            "BOTH" -> "$accel. $brake"
            else -> accel
        }
    }

    private fun demo(from: Int, to: Int, seconds: Double) = AccelSplitTracker.Split(
        fromSpeed = from,
        toSpeed = to,
        seconds = seconds,
        deltaVsPrevious = -0.2,
        deltaVsBest = -0.2,
        isNewBest = true,
    )
}
