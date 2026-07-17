package com.eried.eucplanet.service

import android.content.Context
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.AccelSplitSettings
import java.util.Locale
import kotlin.math.abs

/**
 * Builds the spoken sentence for an acceleration split. Shared by [WheelService]
 * (live announcements) and the settings preview button so the two never drift.
 * Split times are voiced to hundredths (matching RaceBox, e.g. "1.21 seconds");
 * comparison deltas to tenths ("0.2 seconds faster than previous").
 */
object AccelSplitVoice {

    // Below this, a comparison is not worth voicing (rounds to 0.0s at tenths).
    private const val MIN_DELTA = 0.05

    private fun fmtSeconds(sec: Double): String = String.format(Locale.US, "%.2f", sec)

    private fun fmtDelta(sec: Double): String = String.format(Locale.US, "%.1f", abs(sec))

    fun splitText(context: Context, s: AccelSplitTracker.Split, cfg: AccelSplitSettings): String {
        val sb = StringBuilder(
            context.getString(R.string.accel_split_time_fmt, s.fromSpeed, s.toSpeed, fmtSeconds(s.seconds))
        )
        // Prefer the previous-run comparison; the best comparison only speaks
        // when previous did not, so a single split never reads two deltas.
        val prev = s.deltaVsPrevious
        val best = s.deltaVsBest
        var spokeComparison = false
        if (cfg.compareToPrevious && prev != null && abs(prev) >= MIN_DELTA) {
            val id = if (prev < 0) R.string.accel_split_faster_prev else R.string.accel_split_slower_prev
            sb.append(context.getString(id, fmtDelta(prev)))
            spokeComparison = true
        }
        if (cfg.compareToBest) {
            if (s.isNewBest) {
                sb.append(context.getString(R.string.accel_split_best_yet))
            } else if (!spokeComparison && best != null && abs(best) >= MIN_DELTA) {
                val id = if (best < 0) R.string.accel_split_faster_best else R.string.accel_split_slower_best
                sb.append(context.getString(id, fmtDelta(best)))
            }
        }
        return sb.toString()
    }

    /** Representative sentence for the settings preview button, honoring the
     *  rider's comparison toggles against a canned example. */
    fun previewText(context: Context, cfg: AccelSplitSettings): String {
        val demo = AccelSplitTracker.Split(
            fromSpeed = cfg.minSpeed,
            toSpeed = cfg.minSpeed + cfg.increment,
            seconds = 1.21,
            deltaVsPrevious = -0.2,
            deltaVsBest = -0.2,
            isNewBest = true,
        )
        return splitText(context, demo, cfg)
    }
}
