package com.eried.eucplanet.voice

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Opens and shuts the headset button's door.
 *
 * [VoiceTriggerActivity] is declared disabled in the manifest, so the app
 * claims nothing until a rider asks it to. Enabling a component is how that
 * claim is made at runtime: Android reads the manifest filters of enabled
 * components only, so the app appears in the chooser for the voice button
 * exactly while the setting is on, and disappears from it again when the
 * setting goes off.
 *
 * DONT_KILL_APP matters. Without it Android restarts the process to apply the
 * change, which from the rider's side is the app vanishing the moment they
 * touch a switch in settings.
 */
object VoiceTriggerGate {

    private const val TAG = "VoiceTriggerGate"

    /**
     * Make the component's state match [enabled].
     *
     * Cheap to call repeatedly, and called that way on purpose: the settings
     * collector runs it on every change so a value arriving from a Dropbox
     * restore or another device lands as well as one a rider just tapped.
     * The read first is not an optimisation, it is what keeps the log honest
     * about when the claim actually changed.
     */
    fun apply(context: Context, enabled: Boolean) {
        val pm = context.packageManager
        val component = ComponentName(context, VoiceTriggerActivity::class.java)
        val want = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val current = runCatching { pm.getComponentEnabledSetting(component) }.getOrNull()
        if (current == want) return
        runCatching {
            pm.setComponentEnabledSetting(component, want, PackageManager.DONT_KILL_APP)
        }.onSuccess {
            Log.i(TAG, "headset voice button ${if (enabled) "claimed" else "released"}")
            com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
                "Voice: headset button ${if (enabled) "claimed" else "released"}"
            )
        }.onFailure {
            // Nothing the rider can do about it and nothing worth a toast:
            // the switch simply did not take, and the setting still reads the
            // way they left it. Logged so a report of "the button does
            // nothing" has something behind it.
            Log.w(TAG, "could not change the headset trigger component", it)
        }
    }
}
