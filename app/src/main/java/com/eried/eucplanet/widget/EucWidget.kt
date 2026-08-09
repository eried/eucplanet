package com.eried.eucplanet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.eried.eucplanet.MainActivity
import com.eried.eucplanet.R
import com.eried.eucplanet.service.WheelService

/**
 * Home screen widget: speed, trip distance and charge, plus a horn button and a
 * toggle for the periodic voice announcements.
 *
 * Built with RemoteViews rather than Compose or Glance because a widget is
 * inflated inside the launcher's process, where only the classic view set
 * exists. That also puts it outside the app's theme system, so its colours are
 * literals in the layout instead of `MaterialTheme.appColors` tokens.
 *
 * The widget never talks to BLE itself. Buttons fire the same [WheelService]
 * actions the ongoing notification already uses, so there is one implementation
 * of "sound the horn" rather than two.
 *
 * ## Why the last painted state is persisted
 *
 * The launcher re-inflates a widget on its own schedule: a reboot, a rotation,
 * a theme change, or the launcher process being restarted all call [onUpdate]
 * with no telemetry to hand. Without a stored snapshot the widget would flash
 * back to "No wheel" and dashes mid-ride until the next push. SharedPreferences
 * is read synchronously, which a broadcast receiver needs.
 */
class EucWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val views = buildViews(context, Snapshot.load(context))
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_VOICE) {
            // Routed through the service rather than handled here: a broadcast
            // receiver has no Hilt graph and no scope to wait on a settings
            // write.
            context.startService(
                Intent(context, WheelService::class.java)
                    .setAction(WheelService.ACTION_TOGGLE_VOICE)
            )
            return
        }
        super.onReceive(context, intent)
    }

    /**
     * What the widget is currently showing.
     *
     * [connected] is passed in rather than inferred from the presence of
     * telemetry. WheelData is a StateFlow that keeps its last value forever, so
     * "we have numbers" is not the same as "a wheel is on the other end": the
     * first version of this widget inferred it that way and latched on
     * "Connected" for the rest of the process.
     */
    data class Snapshot(
        val connected: Boolean = false,
        val speed: String = DASH,
        val distance: String = DASH,
        val battery: String = DASH,
        val speedUnit: String = "",
        val distanceUnit: String = "",
        val voiceOn: Boolean = false,
    ) {
        companion object {
            private const val PREFS = "euc_widget"
            private const val DASH = "--"

            fun load(context: Context): Snapshot {
                val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                return Snapshot(
                    connected = p.getBoolean("connected", false),
                    speed = p.getString("speed", DASH) ?: DASH,
                    distance = p.getString("distance", DASH) ?: DASH,
                    battery = p.getString("battery", DASH) ?: DASH,
                    speedUnit = p.getString("speedUnit", "") ?: "",
                    distanceUnit = p.getString("distanceUnit", "") ?: "",
                    voiceOn = p.getBoolean("voiceOn", false),
                )
            }

            fun save(context: Context, s: Snapshot) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("connected", s.connected)
                    .putString("speed", s.speed)
                    .putString("distance", s.distance)
                    .putString("battery", s.battery)
                    .putString("speedUnit", s.speedUnit)
                    .putString("distanceUnit", s.distanceUnit)
                    .putBoolean("voiceOn", s.voiceOn)
                    .apply()
            }
        }
    }

    companion object {

        /** Sent by the widget's voice button; handled in [onReceive]. */
        private const val ACTION_TOGGLE_VOICE = "com.eried.eucplanet.widget.TOGGLE_VOICE"

        private const val DASH = "--"

        /** Repaint every placed widget from [snapshot], and remember it. */
        fun render(context: Context, snapshot: Snapshot) {
            Snapshot.save(context, snapshot)
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, EucWidget::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context, snapshot)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        /** True when the rider has at least one widget placed. Lets the service
         *  skip the work entirely for everyone who does not use them. */
        fun isPlaced(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            return manager.getAppWidgetIds(
                ComponentName(context, EucWidget::class.java)
            ).isNotEmpty()
        }

        private fun buildViews(context: Context, s: Snapshot): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_euc)

            views.setTextViewText(
                R.id.widget_status,
                context.getString(
                    if (s.connected) R.string.widget_connected else R.string.widget_disconnected
                )
            )
            // Disconnected shows dashes rather than the last live numbers, which
            // a rider glancing at the launcher would read as current.
            views.setTextViewText(R.id.widget_speed, if (s.connected) s.speed else DASH)
            views.setTextViewText(R.id.widget_distance, if (s.connected) s.distance else DASH)
            views.setTextViewText(R.id.widget_battery, if (s.connected) s.battery else DASH)

            // The rider's own units live in the small caption under each number,
            // so the value itself stays as large as possible.
            views.setTextViewText(
                R.id.widget_speed_label,
                label(context, R.string.widget_speed, s.speedUnit)
            )
            views.setTextViewText(
                R.id.widget_distance_label,
                label(context, R.string.widget_distance, s.distanceUnit)
            )

            views.setTextViewText(
                R.id.widget_btn_voice,
                context.getString(
                    if (s.voiceOn) R.string.widget_voice_on else R.string.widget_voice_off
                )
            )

            // Tapping anywhere that is not a button opens the app.
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            views.setOnClickPendingIntent(
                R.id.widget_btn_horn,
                serviceIntent(context, WheelService.ACTION_HORN, requestCode = 1)
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_voice,
                broadcastIntent(context, ACTION_TOGGLE_VOICE, requestCode = 2)
            )
            return views
        }

        private fun label(context: Context, res: Int, unit: String): String {
            val base = context.getString(res)
            return if (unit.isBlank()) base else "$base ($unit)"
        }

        private fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getService(
                context, requestCode,
                Intent(context, WheelService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private fun broadcastIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, EucWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
