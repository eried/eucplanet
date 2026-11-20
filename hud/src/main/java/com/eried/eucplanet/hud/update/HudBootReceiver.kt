package com.eried.eucplanet.hud.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Opens the HUD app when the HUD starts, so the rider does not have to find
 * it in the unit's launcher every ride. Android only delivers this once the
 * app has been opened by hand after install (and again after a Force stop).
 * Older HUDs (Android 7 and 8) allow the start from here; newer Android
 * versions refuse it, and the rider opens the app by hand as before. A few
 * retries follow, see [HudAutoStart.openAtBoot].
 */
class HudBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        if (!HudAutoStart.isOn(context)) return
        HudAutoStart.openAtBoot(context)
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
