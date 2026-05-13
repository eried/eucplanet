package com.eried.eucplanet.cheats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quake-style console cheats activated from the Settings search bar.
 *
 * Pure in-memory session state — never persisted, never synced. Dies with the
 * app process. The point is the same as in Quake/idTech: a low-friction power
 * gesture for the curious user, with zero risk of accidentally locking in a
 * bad setting because nothing survives a restart.
 *
 * Commands recognized in the search field (case-insensitive, on IME Enter):
 *  - `daredevilN` — set the **display-only** speed multiplier. N is the
 *    percentage offset (any integer, with optional `-`). `daredevil20` shows
 *    speed at 1.20×, `daredevil5` at 1.05×, `daredevil-50` at 0.50×,
 *    `daredevil-99` at 0.01×. `daredevil0` resets to 1.00× (no cheat).
 *    Recording, alarms, and every other computation continues to use the real
 *    speed from `WheelData`.
 *  - `godmode` — toggles a session-wide alarm mute. While active, the
 *    [com.eried.eucplanet.service.AlarmEngine] swallows all rules. Re-typing
 *    `godmode` turns it back off. Lost on app restart.
 *  - `bug` — opens the GitHub "new issue" page in the browser. Doesn't change
 *    any state. Pure shortcut.
 *
 * Other parts of the app read [speedDisplayMultiplier] as a StateFlow so they
 * can react with normal Compose recomposition. [godmode] is a StateFlow too
 * so the AlarmEngine can gate per-tick cheaply.
 */
@Singleton
class CheatState @Inject constructor() {

    private val _speedDisplayMultiplier = MutableStateFlow(1f)
    /** Multiplier applied to the displayed speed (dashboard + watch face). 1.0 = no cheat. */
    val speedDisplayMultiplier: StateFlow<Float> = _speedDisplayMultiplier.asStateFlow()

    private val _godmode = MutableStateFlow(false)
    /** When true, [com.eried.eucplanet.service.AlarmEngine] silences all alarms. */
    val godmode: StateFlow<Boolean> = _godmode.asStateFlow()

    /**
     * Try to interpret [raw] as a cheat command. Returns the toast text on a match
     * (the caller is expected to show it and clear the search field), or null when
     * the input doesn't match anything — let it fall through to normal search.
     */
    fun tryConsume(raw: String): Result? {
        val cmd = raw.trim().lowercase()
        // daredevil<pct> — pct is any integer with optional leading minus.
        // 0 (or -0) resets to no cheat.
        DAREDEVIL_REGEX.matchEntire(cmd)?.let { m ->
            val pct = m.groupValues[1].toIntOrNull() ?: return@let
            _speedDisplayMultiplier.value = 1f + (pct / 100f)
            return Result.Toast("daredevil$pct applied")
        }
        if (cmd == "godmode") {
            _godmode.value = !_godmode.value
            return Result.Toast(if (_godmode.value) "godmode on" else "godmode off")
        }
        if (cmd == "bug") {
            return Result.OpenUrl("https://github.com/eried/eucplanet/issues/new/choose")
        }
        return null
    }

    sealed interface Result {
        val toast: String
        data class Toast(override val toast: String) : Result
        data class OpenUrl(val url: String) : Result {
            override val toast: String = "opening $url"
        }
    }

    companion object {
        private val DAREDEVIL_REGEX = Regex("^daredevil(-?\\d+)$")
    }
}
