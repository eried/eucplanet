package com.eried.eucplanet.hud.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.eried.eucplanet.hud.HudActivity
import com.eried.eucplanet.hud.net.HudDiag
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The rider's "open the HUD app when the HUD starts" choice, kept on the HUD.
 * It comes from the phone with every state frame, but the HUD starts before
 * any phone links, so the last value seen is what counts at start-up. On
 * until the phone says otherwise.
 *
 * A MotoEye shows its own start screen a few seconds into its boot, on top
 * of an app opened right away. So the first open waits until that is past,
 * and later opens follow in case it was slower, each only when the app is
 * not in front.
 *
 * Every step is noted, and the notes go to the phone once it links, so they
 * land in the rider's Service Mode log.
 */
object HudAutoStart {
    private const val PREFS = "hud_autostart"
    private const val KEY_ON = "on"
    private const val MAX_NOTES = 40

    /** Seconds after boot to open the app, each time only if not in front.
     *  The first is the real open; the rest catch a slow start screen. */
    val OPEN_SECONDS = listOf(5, 15, 30)

    /** True while [HudActivity] is resumed. */
    @Volatile
    var inFront: Boolean = false
        private set

    /** While the boot opens run, the app coming and going is noted too,
     *  which shows when the HUD's own screen took over. */
    @Volatile private var watchStart = 0L
    @Volatile private var watchUntil = 0L

    private val notes = ConcurrentLinkedQueue<String>()

    fun isOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ON, true)

    /** Stores [on] when it differs from what is kept, so frames cost nothing. */
    fun remember(context: Context, on: Boolean) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_ON, true) != on) prefs.edit().putBoolean(KEY_ON, on).apply()
    }

    /** Plans the opens. Called at boot. */
    fun scheduleAtBoot(context: Context) {
        watchStart = SystemClock.elapsedRealtime()
        watchUntil = watchStart + (OPEN_SECONDS.last() + 30) * 1000L
        note("HUD started ${watchStart / 1000} s ago, opening the app in ${OPEN_SECONDS.first()} s")
        OPEN_SECONDS.forEachIndexed { i, s -> schedule(context, s, i) }
    }

    /** A planned open fired: opens the app unless it is already in front. */
    fun onRetry(context: Context) {
        if (!isOn(context)) return
        if (inFront) {
            note("app in front, nothing to do")
        } else {
            note("opening the app")
            open(context)
        }
    }

    /** Called from [HudActivity] when it comes to the front or leaves it. */
    fun setInFront(front: Boolean) {
        inFront = front
        if (SystemClock.elapsedRealtime() < watchUntil) {
            note(if (front) "app came to the front" else "app left the front")
        }
    }

    /** The notes not yet sent to the phone, oldest first. */
    fun drainNotes(): List<String> {
        val out = mutableListOf<String>()
        while (true) out += notes.poll() ?: break
        return out
    }

    private fun note(msg: String) {
        val at = (SystemClock.elapsedRealtime() - watchStart) / 1000
        val line = "boot +$at s: $msg"
        HudDiag.log("autostart", line)
        if (notes.size < MAX_NOTES) notes.add(line)
    }

    private fun open(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, HudActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }.onFailure { note("open refused: ${it.message}") }
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
