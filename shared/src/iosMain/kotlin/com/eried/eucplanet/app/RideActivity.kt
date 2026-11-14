package com.eried.eucplanet.app

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.IosWheelLink
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.data.model.NotificationActionType
import com.eried.eucplanet.service.RideNotificationText
import com.eried.eucplanet.service.RideQuickActions
import com.eried.eucplanet.util.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * The lock-screen Live Activity, written in Swift (iosApp/RideLiveActivity.swift,
 * ActivityKit) and registered here at launch. Kotlin decides what it says.
 */
interface RideActivityBridge {
    /** [actionKeys] are NotificationActionType keys, [actionLabels] their labels, in slot order. */
    fun start(title: String, text: String, actionKeys: List<String>, actionLabels: List<String>)
    fun update(title: String, text: String, actionKeys: List<String>, actionLabels: List<String>)
    fun end()
}

/**
 * The iOS counterpart of Android's ongoing ride notification: up while a
 * wheel is connected or guidance runs, with the wheel's name over the same
 * line Android shows ([RideNotificationText], or the next move while
 * navigating), refreshed at most once a second and only when the text
 * changes, as the notification is. Its buttons are the rider's quick
 * actions ([RideQuickActions], as WheelService's), pressed through
 * RideActionIntent (iOS 17) into [press].
 */
object RideActivity {
    var bridge: RideActivityBridge? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var shown = false
    private var lastText: String? = null
    private var lastAt = 0L

    /** What the buttons read besides the telemetry. */
    private data class ButtonState(
        val locked: Boolean,
        val recording: Boolean,
        val lockedDown: Boolean,
        val enabled: Boolean,
        val csv: String,
    )

    fun follow(link: IosWheelLink, data: StateFlow<WheelData>) {
        scope.launch {
            val buttons = combine(
                IosAppGraph.wheel.locked, IosTrips.recording, IosLockdown.controller.engaged,
                // Re-read when the rider changes the picks, not on every settings write.
                IosAppGraph.settings.settings.map { it.notificationActionsEnabled to it.notificationActions }
                    .distinctUntilChanged(),
            ) { locked, recording, lockedDown, picks ->
                ButtonState(locked, recording, lockedDown, picks.first, picks.second)
            }
            // The next move takes over the text while guiding, as on Android.
            val nav = IosAppGraph.navigation.navState
                .map { if (it.active) NavLine(it.primaryText, it.distanceText) else null }
                .distinctUntilChanged()
            combine(link.connectionState, link.connectedDeviceName, data, buttons, nav) { s, n, d, b, g ->
                Ride(s, n, d, b, g)
            }.collect { r -> show(r.state, r.name, r.data, r.buttons, r.nav) }
        }
    }

    /** The guidance line: the next move and how far it is. */
    private data class NavLine(val primary: String, val distance: String)

    private data class Ride(
        val state: ConnectionState,
        val name: String?,
        val data: WheelData,
        val buttons: ButtonState,
        val nav: NavLine?,
    )

    /**
     * A button pressed on the Live Activity. Lock, light, horn and record
     * run through the shared ActionExecutor, as every other surface's press
     * does; Disconnect and Stop all act on the app, as WheelService's intents
     * do. A stale button pressed after lockdown engaged does nothing.
     */
    fun press(key: String) {
        val type = NotificationActionType.byKey(key) ?: return
        if (IosLockdown.controller.isEngaged()) return
        scope.launch {
            when (type) {
                NotificationActionType.DISCONNECT -> IosAppGraph.wheel.disconnect()
                // The dashboard's Stop all on the iPhone: the trip meter cleared
                // and the wheel let go (an iOS app does not end itself).
                NotificationActionType.STOP_ALL ->
                    IosDashboardPlatform(IosAppGraph.wheel, IosTripMeter.meter).stopAllAndExit()
                NotificationActionType.STOP_NAV -> IosAppGraph.navigation.stop()
                else -> RideQuickActions.executorKey(type, IosTrips.recording.value)?.let { k ->
                    IosAppGraph.actions.executeAction(k, IosAppGraph.settings.currentOrLoad())
                }
            }
        }
    }

    private suspend fun show(state: ConnectionState, name: String?, data: WheelData, buttons: ButtonState, nav: NavLine?) {
        val b = bridge ?: return
        if (state == ConnectionState.DISCONNECTED && nav == null) {
            if (shown) b.end()
            shown = false
            lastText = null
            return
        }
        val settings = IosAppGraph.settings.current.value
        val title = name ?: settings.lastDeviceName ?: getString(Res.string.app_name)
        val text = if (nav != null) {
            listOf(nav.primary, nav.distance).filter { it.isNotBlank() }.joinToString("  \u00b7  ")
                .ifBlank { getString(Res.string.nav_title) }
        } else if (data.speed > 0) {
            val unit = Units.effectiveSpeedUnit(settings)
            RideNotificationText.telemetry(
                Units.speed(data.speed, unit), getString(Units.speedUnitRes(unit)), data.batteryPercent, data.voltage,
            )
        } else RideNotificationText.state(state)
        val quick = RideQuickActions.resolve(
            enabled = buttons.enabled,
            csv = buttons.csv,
            lockedDown = buttons.lockedDown,
            navActive = nav != null,
            locked = buttons.locked,
            lightOn = data.lightOn,
            recording = buttons.recording,
        )
        val keys = quick.map { it.type.key }
        val labels = quick.map { getString(it.label) }
        val line = "$title\n$text\n${labels.joinToString("|")}"
        val now = System.currentTimeMillis()
        if (!shown) {
            b.start(title, text, keys, labels)
            shown = true
        } else if (line != lastText && now - lastAt >= 1000) {
            b.update(title, text, keys, labels)
        } else return
        lastText = line
        lastAt = now
    }
}
