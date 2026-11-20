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
 * A MotoEye shows its own start screen late in its boot, on top of an app
 * opened at boot. So after the first open, the HUD opens the app again a few
 * times over the first minute, each time only when it is not in front.
 *
 * Every step is noted, and the notes go to the phone once it links, so they
 * land in the rider's Service Mode log.
 */
object HudAutoStart {
    private const val PREFS = "hud_autostart"
    private const val KEY_ON = "on"
    private const val KEY_TEST_ID = "test_id"
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_SECONDS = "seconds"
    private const val KIND_BOOT = "boot"
    private const val KIND_TEST = "test"
    private const val MAX_NOTES = 40

    /** Seconds after boot to bring the app back to the front, if needed. */
    val RETRY_SECONDS = listOf(5, 15, 30, 60)

    /** Seconds the phone's Test waits before opening the app again. */
    const val TEST_DELAY_SECONDS = 10

    /** True while [HudActivity] is resumed. */
    @Volatile
    var inFront: Boolean = false
        private set

    /** While a boot or a test runs, the app coming and going is noted too,
     *  which shows when the HUD's own screen took over. */
    @Volatile private var watchKind = ""
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

    /** Opens the app now and plans the retries. Called at boot. */
    fun openAtBoot(context: Context) {
        watch(KIND_BOOT, RETRY_SECONDS.last() + 30)
        note("HUD started ${SystemClock.elapsedRealtime() / 1000} s ago, opening the app")
        open(context)
        RETRY_SECONDS.forEachIndexed { i, s -> schedule(context, KIND_BOOT, s, i) }
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
        watch(KIND_TEST, TEST_DELAY_SECONDS + 20)
        note("going to the home screen")
        schedule(context, KIND_TEST, TEST_DELAY_SECONDS, RETRY_SECONDS.size)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { note("home screen refused: ${it.message}") }
    }

    /** A planned retry fired: opens the app unless it is already in front. */
    fun onRetry(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_BOOT
        // The test runs with the switch off, so only boot retries obey it.
        if (kind == KIND_BOOT && !isOn(context)) return
        if (inFront) {
            note("retry: app in front, nothing to do")
        } else {
            note("retry: app hidden, opening it again")
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

    private fun watch(kind: String, seconds: Int) {
        watchKind = kind
        watchStart = SystemClock.elapsedRealtime()
        watchUntil = watchStart + seconds * 1000L
    }

    private fun note(msg: String) {
        val at = (SystemClock.elapsedRealtime() - watchStart) / 1000
        val line = "$watchKind +$at s: $msg"
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

    private fun schedule(context: Context, kind: String, seconds: Int, slot: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            slot,
            Intent(context, HudRetryReceiver::class.java)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_SECONDS, seconds),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + seconds * 1000L, pi)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
