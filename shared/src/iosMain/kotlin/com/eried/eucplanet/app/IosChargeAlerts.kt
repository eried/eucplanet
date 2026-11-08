package com.eried.eucplanet.app

import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.service.ChargeAlertPolicy
import com.eried.eucplanet.ui.charging.ChargingMonitorLaunch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * The charge alerts on iOS, as WheelService.checkChargeAlerts posts them on
 * Android: the same ChargeAlertPolicy on every frame, the same titles and
 * text, as a local notification. Tapping one opens the charging monitor.
 * Permission is asked the first time an alert is switched on.
 */
internal object IosChargeAlerts {
    private const val TAG = "ChargeAlerts"
    /** One id, so a newer alert replaces the older, like Android's NOTIFICATION_ID. */
    private const val REQUEST_ID = "charge_alert"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var state = ChargeAlertPolicy.State()
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()
    /** Held here: the notification center keeps its delegate weakly. */
    private val delegate = Delegate()

    fun start() {
        center.delegate = delegate
        scope.launch {
            IosAppGraph.settings.settings
                .map { it.chargingNotify80 || it.chargingNotifyFull || it.chargingNotifyCustom }
                .distinctUntilChanged()
                .collect { wanted -> if (wanted) askPermission() }
        }
        scope.launch {
            IosAppGraph.wheel.wheelData.collect { data ->
                val settings = IosAppGraph.settings.current.value
                val step = ChargeAlertPolicy.step(
                    state,
                    IosAppGraph.wheel.chargeStatus.value,
                    data.batteryPercent,
                    want80 = settings.chargingNotify80,
                    wantFull = settings.chargingNotifyFull,
                    customMark = if (settings.chargingNotifyCustom) settings.chargingNotifyCustomPct else 0,
                )
                state = step.state
                when (step.alert) {
                    ChargeAlertPolicy.Alert.AT_80 -> post(
                        getString(Res.string.charge_alert_80_title), getString(Res.string.charge_alert_80_text),
                    )
                    ChargeAlertPolicy.Alert.FULL -> post(
                        getString(Res.string.charge_alert_full_title), getString(Res.string.charge_alert_full_text),
                    )
                    ChargeAlertPolicy.Alert.CUSTOM -> post(
                        IosPhoneDevice.text(Res.string.charge_alert_custom_title, settings.chargingNotifyCustomPct),
                        getString(Res.string.charge_alert_custom_text),
                    )
                    ChargeAlertPolicy.Alert.NONE -> Unit
                }
            }
        }
    }

    private fun askPermission() {
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
            Log.i(TAG, "notifications allowed: $granted")
        }
    }

    private fun post(title: String, text: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(text)
            setSound(UNNotificationSound.defaultSound)
        }
        val request = UNNotificationRequest.requestWithIdentifier(REQUEST_ID, content, null)
        center.addNotificationRequest(request) { error ->
            if (error != null) Log.w(TAG, "not posted: ${error.localizedDescription}")
        }
    }

    /** Shows the alert while the app is open too, and opens the monitor on a tap. */
    private class Delegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) {
            withCompletionHandler(
                UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList or
                    UNNotificationPresentationOptionSound,
            )
        }

        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            didReceiveNotificationResponse: UNNotificationResponse,
            withCompletionHandler: () -> Unit,
        ) {
            if (didReceiveNotificationResponse.notification.request.identifier == REQUEST_ID) {
                ChargingMonitorLaunch.request()
            }
            withCompletionHandler()
        }
    }
}
