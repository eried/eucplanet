package com.eried.eucplanet.hud.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** A planned retry from [HudAutoStart]: opens the app if it is not in front. */
class HudRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HudAutoStart.onRetry(context, intent)
    }
}
