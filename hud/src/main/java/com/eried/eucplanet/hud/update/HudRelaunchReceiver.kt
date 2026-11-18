package com.eried.eucplanet.hud.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eried.eucplanet.hud.HudActivity

/**
 * Brings the HUD back up after it was updated. Android stops an app to
 * replace it and does not start it again, which on a HUD would leave the
 * rider at the unit's launcher. Newer Android versions refuse a start from
 * the background; there the rider opens the app again by hand.
 */
class HudRelaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            context.startActivity(
                Intent(context, HudActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
