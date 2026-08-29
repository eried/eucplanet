package com.eried.eucplanet.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.eried.eucplanet.R

/**
 * The charge alert notification, assembled in one place.
 *
 * Pulled out of [WheelService] so it can be posted from a test and looked at.
 * Its first version wore the ongoing notification's Bluetooth icon, which no
 * build could have caught: the resource existed, the notification posted, and
 * the only symptom was the wrong glyph in a status bar.
 */
object ChargeAlertNotification {

    /** Separate from the ongoing channel so its HIGH importance does not make
     *  the permanent notification noisy, and either can be muted alone. */
    const val CHANNEL_ID = "charge_alerts"
    const val NOTIFICATION_ID = 42

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.charge_alert_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.charge_alert_channel_description)
                    enableVibration(true)
                    setShowBadge(true)
                }
            )
    }

    fun build(
        context: Context,
        titleRes: Int,
        textRes: Int,
        contentIntent: PendingIntent?,
    ) = NotificationCompat.Builder(context, CHANNEL_ID)
        // A battery, not the ongoing notification's Bluetooth glyph: the
        // status bar should say what happened, and what happened is about the
        // pack, not the link to it.
        .setSmallIcon(R.drawable.ic_notification_charge)
        .setContentTitle(context.getString(titleRes))
        .setContentText(context.getString(textRes))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setDefaults(NotificationCompat.DEFAULT_ALL)
        .setAutoCancel(true)
        .setContentIntent(contentIntent)
        .build()
}
