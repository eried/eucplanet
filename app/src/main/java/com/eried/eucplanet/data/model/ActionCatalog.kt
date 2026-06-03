package com.eried.eucplanet.data.model

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector
import com.eried.eucplanet.R

/**
 * Action layer — single source of truth for every rider-triggerable command.
 *
 * Surfaces that can bind an action (Flic, volume keys, watch, dashboard tile,
 * future alarm-triggers, voice shortcuts, etc.) all query this catalog
 * instead of maintaining their own list. Adding a new action means adding
 * one entry to [ActionCatalog.all] — no other file should need to grow a
 * new branch.
 *
 * The discriminator that picks which surfaces an action lands on is the
 * [ActionSpec.isEyesFreeSafe] flag:
 *  - Eyes-free safe → bindable on every physical / hands-free surface
 *    (Flic, volume key, watch, future trigger / alarm-fire).
 *  - Not eyes-free → only the surfaces where the rider is looking at the
 *    screen ([ActionSurface.DASHBOARD] / [ActionSurface.VOICE]).
 *
 * [ActionSpec.statusReader] returns "is this action's effect currently
 * active?" so any surface can render an active highlight. Null means the
 * action has no resting state (HORN, VOICE_ANNOUNCE, RESET_TRIP, etc.).
 *
 * Dispatch is unified through [dispatchAction]: eyes-free actions route
 * through `FlicManager` (the shared physical-surface executor); dashboard-only
 * actions ([ActionUi]) are handled by whichever Compose surface fired them
 * (the dashboard action tile or the service-mode debug overlay).
 */

/**
 * Where an action binding can live. Adding a new surface means defining
 * what set of actions it accepts (eyes-free vs all) in [ActionCatalog.keysFor].
 */
enum class ActionSurface {
    /** Tap-to-fire grid tile in the dashboard layout editor. */
    DASHBOARD,

    /** Hardware Flic button. */
    FLIC,

    /** Phone volume hardware keys. */
    VOLUME_KEY,

    /** WearOS device button (stem or on-screen tap). */
    WATCH,

    /** Future: metric-based condition triggers (e.g. "speed > 50 → action"). */
    TRIGGER,

    /** Future: voice command shortcut ("hey planet, lights on"). */
    VOICE,

    /** Future: action fired automatically when an alarm condition trips. */
    ALARM_FIRE
}

/**
 * Read-only snapshot of the running app state, passed to
 * [ActionSpec.statusReader] so it can decide whether the action's effect
 * is currently active. Carrier-only — no Hilt scope, no flows; the caller
 * builds a snapshot per dispatch from whatever sources it has.
 *
 * Fields are nullable / unknown-default to avoid forcing callers to plumb
 * every signal. A reader that needs information not provided returns
 * false (or whatever its safe default is).
 */
data class StatusContext(
    val wheel: WheelData = WheelData(),
    /** True while [com.eried.eucplanet.data.repository.TripRepository.recording] is on. */
    val tripRecording: Boolean = false,
    /** Imperial-unit toggle state from settings. */
    val imperialUnits: Boolean = false,
    /** Alarms-muted flag from settings. Not yet wired upstream — defaults false. */
    val alarmsMuted: Boolean = false,
    /** True when the wheel is currently in safety / legal mode. */
    val safetyActive: Boolean = false
)

data class ActionSpec(
    /** Stable identifier persisted in settings (settings, group definitions, Flic bindings, watch bindings). */
    val key: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector? = null,
    /**
     * True when this action is safe to fire without looking at the screen.
     * Drives which physical-trigger surfaces the action appears on.
     * Borderline cases (TOGGLE_UNITS, RESET_TRIP, MUTE_ALARMS) are kept
     * false today because riders typically want visual confirmation.
     */
    val isEyesFreeSafe: Boolean = false,
    /**
     * Reads the current "is this action's effect currently active?" state.
     * - Null → one-shot action with no state (HORN, VOICE_ANNOUNCE, MEDIA_*).
     * - Returns true → surface should render the binding as active (e.g.
     *   highlight the dashboard tile, fill the watch button icon).
     * For paired one-direction setters like SAFETY_ON / SAFETY_OFF, the
     * reader returns true when pressing would be a no-op, so the UI can
     * disable or de-emphasize the button.
     */
    val statusReader: ((StatusContext) -> Boolean)? = null
)

object ActionCatalog {

    val all: List<ActionSpec> = listOf(
        // ---- Eyes-free safe (Flic / volume / watch / trigger / alarm-fire bindable) ----
        ActionSpec(
            key = "HORN",
            labelRes = R.string.action_chip_horn,
            icon = Icons.Filled.Campaign,
            isEyesFreeSafe = true
        ),
        ActionSpec(
            key = "LIGHT_TOGGLE",
            labelRes = R.string.action_chip_light,
            icon = Icons.Filled.FlashlightOn,
            isEyesFreeSafe = true,
            statusReader = { it.wheel.lightOn }
        ),
        ActionSpec(
            key = "LOCK_TOGGLE",
            labelRes = R.string.action_chip_lock,
            icon = Icons.Filled.Lock,
            isEyesFreeSafe = true,
            // pcMode == 0 means locked. -1 (unknown) returns false so the
            // tile doesn't render an "active" state before the wheel has
            // sent a mode frame.
            statusReader = { it.wheel.pcMode == 0 }
        ),
        ActionSpec(
            key = "SAFETY_TOGGLE",
            labelRes = R.string.action_chip_safety,
            icon = Icons.Filled.Shield,
            isEyesFreeSafe = true,
            statusReader = { it.safetyActive }
        ),
        ActionSpec(
            key = "SAFETY_ON",
            labelRes = R.string.action_chip_safety_on,
            icon = Icons.Filled.Shield,
            isEyesFreeSafe = true,
            // Highlight when ALREADY in safety mode — pressing this is a
            // no-op in that case, so the active state warns the rider.
            statusReader = { it.safetyActive }
        ),
        ActionSpec(
            key = "SAFETY_OFF",
            labelRes = R.string.action_chip_safety_off,
            icon = Icons.Filled.Shield,
            isEyesFreeSafe = true,
            // Mirror of SAFETY_ON — highlight when already off.
            statusReader = { !it.safetyActive }
        ),
        ActionSpec(
            key = "VOICE_ANNOUNCE",
            labelRes = R.string.action_chip_voice,
            icon = Icons.Filled.RecordVoiceOver,
            isEyesFreeSafe = true
        ),
        ActionSpec(
            key = "RECORD_TOGGLE",
            labelRes = R.string.action_chip_record,
            icon = Icons.Filled.FiberManualRecord,
            isEyesFreeSafe = true,
            statusReader = { it.tripRecording }
        ),
        ActionSpec(
            key = "RECORD_START",
            labelRes = R.string.action_chip_record_start,
            icon = Icons.Filled.FiberManualRecord,
            isEyesFreeSafe = true,
            // Already-recording = pressing this is a no-op; highlight to
            // signal "no change will happen if you tap this".
            statusReader = { it.tripRecording }
        ),
        ActionSpec(
            key = "RECORD_STOP",
            labelRes = R.string.action_chip_record_stop,
            icon = Icons.Filled.FiberManualRecord,
            isEyesFreeSafe = true,
            statusReader = { !it.tripRecording }
        ),
        ActionSpec(
            key = "MEDIA_PLAY_PAUSE",
            labelRes = R.string.action_chip_media_play,
            icon = Icons.Filled.PlayArrow,
            isEyesFreeSafe = true
        ),
        ActionSpec(
            key = "MEDIA_NEXT",
            labelRes = R.string.action_chip_media_next,
            icon = Icons.Filled.SkipNext,
            isEyesFreeSafe = true
        ),
        ActionSpec(
            key = "MEDIA_PREVIOUS",
            labelRes = R.string.action_chip_media_prev,
            icon = Icons.Filled.SkipPrevious,
            isEyesFreeSafe = true
        ),

        // ---- Dashboard-only (need screen attention or are destructive) ----
        ActionSpec(
            key = "OPEN_NAVIGATION",
            labelRes = R.string.action_chip_open_navigation,
            icon = Icons.Filled.Navigation
        ),
        ActionSpec(
            key = "OPEN_STUDIO",
            labelRes = R.string.action_chip_open_studio,
            icon = Icons.Filled.PhotoCamera
        ),
        ActionSpec(
            key = "OPEN_ABOUT",
            labelRes = R.string.action_chip_open_about,
            icon = Icons.Filled.Info
        ),
        ActionSpec(
            key = "OPEN_SERVICE",
            labelRes = R.string.action_chip_open_service,
            icon = Icons.Filled.Build
        ),
        ActionSpec(
            key = "OPEN_TRIPS",
            labelRes = R.string.action_chip_open_trips,
            icon = Icons.AutoMirrored.Filled.List
        ),
        ActionSpec(
            key = "MUTE_ALARMS",
            labelRes = R.string.action_chip_mute_alarms,
            icon = Icons.AutoMirrored.Filled.VolumeOff,
            statusReader = { it.alarmsMuted }
        ),
        ActionSpec(
            key = "RESET_TRIP",
            labelRes = R.string.action_chip_reset_trip,
            icon = Icons.Filled.Restore
        ),
        ActionSpec(
            key = "TOGGLE_UNITS",
            labelRes = R.string.action_chip_toggle_units,
            icon = Icons.Filled.SwapHoriz,
            statusReader = { it.imperialUnits }
        )
    )

    private val byKeyMap: Map<String, ActionSpec> = all.associateBy { it.key }

    fun byKey(key: String): ActionSpec? = byKeyMap[key]

    /**
     * The list of action keys eligible for [surface], in catalog declaration
     * order. Callers that need alphabetical ordering (like the dashboard
     * pool) sort by display label themselves.
     */
    fun keysFor(surface: ActionSurface): List<String> = when (surface) {
        // Riders looking at the screen can pick anything.
        ActionSurface.DASHBOARD, ActionSurface.VOICE -> all.map { it.key }
        // Physical / hands-free / automated surfaces only see eyes-free actions.
        ActionSurface.FLIC,
        ActionSurface.VOLUME_KEY,
        ActionSurface.WATCH,
        ActionSurface.TRIGGER,
        ActionSurface.ALARM_FIRE -> all.filter { it.isEyesFreeSafe }.map { it.key }
    }

    /**
     * The set of surfaces an action can be bound to. Derived from
     * [ActionSpec.isEyesFreeSafe]: eyes-free actions land on every
     * surface, screen-required actions only land on DASHBOARD + VOICE.
     */
    fun surfacesFor(spec: ActionSpec): Set<ActionSurface> = if (spec.isEyesFreeSafe) {
        ActionSurface.values().toSet()
    } else {
        setOf(ActionSurface.DASHBOARD, ActionSurface.VOICE)
    }
}
