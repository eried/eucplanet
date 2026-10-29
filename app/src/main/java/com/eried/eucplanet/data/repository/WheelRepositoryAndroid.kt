package com.eried.eucplanet.data.repository

import android.content.Context
import com.eried.eucplanet.data.db.WheelProfileDao
import com.eried.eucplanet.data.model.WheelProfile
import com.eried.eucplanet.platform.getString
import org.jetbrains.compose.resources.StringResource

/** The phone itself, for the shared [WheelRepository]. */
class AndroidPhoneDevice(private val context: Context) : PhoneDevice {

    override fun batteryPercent(): Int =
        (context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    /** Phone-side fallback for wheel families that lack a horn opcode.
     *  Brief tone + vibration -- not a real horn but at least the rider
     *  knows the tap registered. Best-effort; silently catches every
     *  audio / vibrator error so a locked-down audio policy never throws
     *  out of a horn tap. */
    override fun hornFallback() {
        try {
            val tone = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_MUSIC, 100
            )
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 400)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { tone.release() } catch (_: Throwable) {}
            }, 600L)
        } catch (_: Throwable) {}
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (vib != null && android.os.Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        300L, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(300L)
            }
        } catch (_: Throwable) {}
    }

    override fun text(res: StringResource, vararg args: Any): String =
        if (args.isEmpty()) context.getString(res) else context.getString(res, *args)
}

/** The Room DAO as the shared [WheelProfiles]. */
class DaoWheelProfiles(private val dao: WheelProfileDao) : WheelProfiles {
    override suspend fun getByName(name: String): WheelProfile? = dao.getByName(name)
    override suspend fun upsert(profile: WheelProfile) = dao.upsert(profile)
}
