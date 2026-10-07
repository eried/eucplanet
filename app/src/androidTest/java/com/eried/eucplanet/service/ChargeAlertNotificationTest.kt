package com.eried.eucplanet.service

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eried.eucplanet.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The charge alert, posted for real.
 *
 * It shipped wearing the ongoing notification's Bluetooth icon, and nothing
 * could have caught that: the resource existed, the notification posted, and
 * the only symptom was the wrong glyph in a rider's status bar. So the icon is
 * asserted, and the notification is actually posted so the shade can be looked
 * at rather than reasoned about.
 */
@RunWith(AndroidJUnit4::class)
class ChargeAlertNotificationTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun itWearsTheBatteryIcon() {
        val n = ChargeAlertNotification.build(
            ctx, R.string.charge_alert_80_title, R.string.charge_alert_80_text, null,
        )
        assertNotNull(n)
        @Suppress("DEPRECATION")
        assertEquals(
            "the charge alert is not using the battery icon",
            R.drawable.ic_notification_charge, n.icon,
        )
    }

    @Test fun itPostsAndCanBeSeen() {
        ChargeAlertNotification.ensureChannel(ctx)
        val n = ChargeAlertNotification.build(
            ctx, R.string.charge_alert_80_title, R.string.charge_alert_80_text, null,
        )
        ctx.getSystemService(NotificationManager::class.java)
            .notify(ChargeAlertNotification.NOTIFICATION_ID, n)
        // Left posted on purpose: the point of this one is the screenshot.
        val active = ctx.getSystemService(NotificationManager::class.java).activeNotifications
        assertEquals(
            "the alert did not reach the shade",
            1,
            active.count { it.id == ChargeAlertNotification.NOTIFICATION_ID },
        )
    }
}
