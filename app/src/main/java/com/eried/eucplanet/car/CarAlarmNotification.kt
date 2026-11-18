package com.eried.eucplanet.car

import android.app.NotificationManager
import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.eried.eucplanet.R

/**
 * An alarm, on the car screen, over whatever the rider is looking at.
 *
 * Android Auto gives a non-navigation app no way to stay on screen beside
 * Maps for long, and the rider navigating is exactly when a PWM or battery
 * warning matters most. A notification is the one channel that crosses: the
 * host draws it as a heads-up card over the navigation it is covering, then
 * takes it away again.
 *
 * [CarAppExtender] is what makes the host treat an ordinary notification as a
 * car one. Without it the notification is posted to the phone and the car
 * never sees it.
 *
 * Deliberately not a copy of the phone's alarm notification. An alarm that
 * reaches a rider mid-corner has to be readable in the time it takes to
 * glance: the rule's name and the value that broke it, nothing else.
 */
object CarAlarmNotification {

    /** Its own channel, so a rider can silence car alarms and keep the phone's,
     *  and so HIGH importance here never makes the ongoing notification noisy. */
    const val CHANNEL_ID = "car_alarms_quiet"

    /** The first channel, which rang the phone's notification sound over the
     *  rule's own beep. A channel keeps the sound it was made with, so the
     *  quiet one is a new channel and this one is removed. */
    private const val OLD_CHANNEL_ID = "car_alarms"

    /** Whether a car is attached. False until [start] hears otherwise, so a
     *  phone with no car gets no card for an alarm it has already sounded. */
    @Volatile private var carAttached = false

    /** Follows the car for as long as the process lives. Main thread, once. */
    fun start(context: Context) {
        CarConnection(context.applicationContext).type.observeForever { type ->
            carAttached = isAttached(type)
        }
    }

    internal fun isAttached(connectionType: Int?) =
        connectionType != null && connectionType != CarConnection.CONNECTION_TYPE_NOT_CONNECTED

    /** One id, reused. A second alarm replaces the first rather than stacking
     *  a pile of cards over the map; the newest reading is the one that counts
     *  and a queue of stale ones is worse than nothing while riding. */
    const val NOTIFICATION_ID = 43

    fun ensureChannel(context: Context) {
        CarNotificationManager.from(context).deleteNotificationChannel(OLD_CHANNEL_ID)
        CarNotificationManager.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.car_alarm_channel_name))
                .setDescription(context.getString(R.string.car_alarm_channel_description))
                // The alarm itself already beeps and buzzes through the rule's
                // own actions. A second buzz or chime from the notification
                // would double every alert.
                .setVibrationEnabled(false)
                .setSound(null, null)
                .setShowBadge(false)
                .build()
        )
    }

    /**
     * Posts [title] with [text] to the car screen.
     *
     * Does nothing when no car is attached. With one, the phone notification
     * it rides on is silent and badge-less.
     */
    fun post(context: Context, title: String, text: String) {
        if (!carAttached) return
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_charge)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(title)
                    .setContentText(text)
                    .setImportance(NotificationManager.IMPORTANCE_HIGH)
                    .build()
            )
        runCatching {
            CarNotificationManager.from(context).notify(NOTIFICATION_ID, builder)
        }
    }
}
