package com.eried.eucplanet.hud.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.eried.eucplanet.hud.HudActivity

/**
 * The rider's "open the HUD app when the HUD starts" choice, kept on the HUD.
 * It comes from the phone with every state frame, but the HUD starts before
 * any phone links, so the last value seen is what counts at start-up. On
 * until the phone says otherwise.
 *
 * A MotoEye shows its own start screen late in its boot, on top of an app
 * opened at boot. So after the first open, the HUD opens the app again a few
 * times over the first minute, each time only when it is not in front.
 */
object HudAutoStart {
    private const val PREFS = "hud_autostart"
    private const val KEY_ON = "on"
    private const val KEY_TEST_ID = "test_id"

    /** Seconds after boot to bring the app back to the front, if needed. */
    val RETRY_SECONDS = listOf(5, 15, 30, 60)

    /** Seconds the phone's Test waits before opening the app again. */
    const val TEST_DELAY_SECONDS = 10

    /** True while [HudActivity] is resumed. */
    @Volatile
    var inFront: Boolean = false

    fun isOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ON, true)

    /** Stores [on] when it differs from what is kept, so frames cost nothing. */
    fun remember(context: Context, on: Boolean) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_ON, true) != on) prefs.edit().putBoolean(KEY_ON, on).apply()
    }

    /** Opens the app now and plans the retries. Called at boot. */
    fun openAtBoot(context: Context) {
        open(context)
        RETRY_SECONDS.forEachIndexed { i, s -> schedule(context, s, i) }
    }

    /**
     * The phone's Test: runs once per [id]. Leaves the app for the HUD's home
     * screen, then opens it again after [TEST_DELAY_SECONDS], the way a late
     * retry at boot would.
     */
    fun testIfNew(context: Context, id: Long) {
        if (id == 0L) return
        val prefs = prefs(context)
        if (prefs.getLong(KEY_TEST_ID, 0L) == id) return
        prefs.edit().putLong(KEY_TEST_ID, id).apply()
        schedule(context, TEST_DELAY_SECONDS, RETRY_SECONDS.size)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Brings the app to the front unless it already is. */
    fun openIfHidden(context: Context) {
        if (!inFront) open(context)
    }

    private fun open(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, HudActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    private fun schedule(context: Context, seconds: Int, slot: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            slot,
            Intent(context, HudRetryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + seconds * 1000L, pi)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
