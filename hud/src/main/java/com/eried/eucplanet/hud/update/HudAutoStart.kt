package com.eried.eucplanet.hud.update

import android.content.Context

/**
 * The rider's "open the HUD app when the HUD starts" choice, kept on the HUD.
 * It comes from the phone with every state frame, but the HUD starts before
 * any phone links, so the last value seen is what counts at start-up. On
 * until the phone says otherwise.
 */
object HudAutoStart {
    private const val PREFS = "hud_autostart"
    private const val KEY_ON = "on"

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    /** Stores [on] when it differs from what is kept, so frames cost nothing. */
    fun remember(context: Context, on: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ON, true) != on) prefs.edit().putBoolean(KEY_ON, on).apply()
    }
}
