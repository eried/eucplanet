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
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.service.WheelService
import com.eried.eucplanet.util.Units

/**
 * Home screen widget: speed, trip distance and charge, plus a horn button and a
 * toggle for the periodic voice announcements.
 *
 * Built with RemoteViews rather than Compose or Glance because a widget is
 * inflated inside the launcher's process, where only the classic view set
 * exists. That also puts it outside the app's theme system, so its colours are
 * literals in the layout instead of `MaterialTheme.appColors` tokens.
 *
 * The widget never talks to BLE itself. Buttons fire the same
 * [WheelService] actions the ongoing notification already uses, so there is one
 * implementation of "sound the horn" rather than two.
 */
class EucWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // A launcher-driven update (widget added, phone rebooted, layout
        // reloaded). There is no telemetry to hand here, so paint the resting
        // state; WheelService overwrites it on the next frame if a wheel is up.
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, null, voiceOn = null))
        }
    }

    companion object {

        /** Sent by the widget's voice button; handled in [onReceive]. */
        private const val ACTION_TOGGLE_VOICE = "com.eried.eucplanet.widget.TOGGLE_VOICE"

        /**
         * Repaint every placed widget.
         *
         * [data] null means "no wheel connected", which shows dashes rather than
         * a stale set of numbers the rider might read as live.
         */
        fun render(context: Context, data: WheelData?, voiceOn: Boolean?) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, EucWidget::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context, data, voiceOn)
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

        private fun buildViews(
            context: Context,
            data: WheelData?,
            voiceOn: Boolean?,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_euc)
            val dash = context.getString(R.string.widget_placeholder_value)

            if (data == null) {
                views.setTextViewText(R.id.widget_status, context.getString(R.string.widget_disconnected))
                views.setTextViewText(R.id.widget_speed, dash)
                views.setTextViewText(R.id.widget_distance, dash)
                views.setTextViewText(R.id.widget_battery, dash)
            } else {
                views.setTextViewText(R.id.widget_status, context.getString(R.string.widget_connected))
                views.setTextViewText(R.id.widget_speed, "%.0f".format(data.speed))
                views.setTextViewText(R.id.widget_distance, "%.1f".format(data.tripDistance))
                views.setTextViewText(R.id.widget_battery, "${data.batteryPercent}%")
            }

            views.setTextViewText(
                R.id.widget_btn_voice,
                context.getString(
                    if (voiceOn == true) R.string.widget_voice_on else R.string.widget_voice_off
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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_VOICE) {
            // Routed through the service rather than written here: the widget
            // runs in a broadcast receiver with no Hilt graph and no scope to
            // wait on a DataStore write.
            context.startService(
                Intent(context, WheelService::class.java)
                    .setAction(WheelService.ACTION_TOGGLE_VOICE)
            )
            return
        }
        super.onReceive(context, intent)
    }
}
