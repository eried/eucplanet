package com.eried.eucplanet.data.model

import androidx.annotation.StringRes
import com.eried.eucplanet.R

/**
 * The four states the speed splits can be in, in the order a button cycles
 * them: off, accelerating only, braking only, both.
 *
 * The settings store this as two fields, [AccelSplitSettings.enabled] and
 * [AccelSplitSettings.direction], because the switch and the direction picker
 * on the settings screen are two controls. A dashboard button that cycles
 * through the states needs them to be one thing, and this is the one place
 * that says how the two fields and the four states map onto each other, so
 * the button, the settings screen and the tracker cannot disagree about what
 * "Both" means.
 */
enum class AccelSplitMode(
    val enabled: Boolean,
    /** The [AccelSplitSettings.direction] value, or empty for off. */
    val direction: String,
    /** The dashboard tile's label in this state. */
    @StringRes val tileLabelRes: Int,
) {
    OFF(false, "", R.string.action_splits_off),
    ACCEL(true, "ACCEL", R.string.action_splits_accel),
    BRAKE(true, "BRAKE", R.string.action_splits_brake),
    BOTH(true, "BOTH", R.string.action_splits_both);

    /** The state a tap moves to. Wraps from [BOTH] back to [OFF]. */
    fun next(): AccelSplitMode = entries[(ordinal + 1) % entries.size]

    /**
     * The settings with this state applied.
     *
     * Off only clears the switch and leaves the direction alone, so the
     * settings screen's own switch still comes back on in the direction the
     * rider last chose there.
     */
    fun applyTo(settings: AccelSplitSettings): AccelSplitSettings =
        if (this == OFF) settings.copy(enabled = false)
        else settings.copy(enabled = true, direction = direction)

    companion object {
        /** The state the settings are in. An unknown direction reads as accel,
         *  which is the settings field's own default. */
        fun of(settings: AccelSplitSettings): AccelSplitMode =
            if (!settings.enabled) OFF
            else entries.firstOrNull { it.enabled && it.direction == settings.direction } ?: ACCEL
    }
}
