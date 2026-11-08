package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.NotificationActionType
import com.eried.eucplanet.resources.*
import org.jetbrains.compose.resources.StringResource

/** One button on the ride notification or Live Activity: what it does and what it says. */
data class RideQuickAction(val type: NotificationActionType, val label: StringResource)

/**
 * Which quick-action buttons the ride surface shows right now, in slot order:
 * Android's ongoing notification (WheelService) and the iPhone's Live
 * Activity read the same answer.
 *
 * Labels follow the state (Lock or Unlock, Light on or off, Record or Stop).
 * Stop navigation shows only while navigating, since neither surface can grey
 * a button out. Nothing shows when the rider switched the buttons off, or
 * while Legal Mode Lockdown runs: the buttons are another way to reach a
 * gated action.
 */
object RideQuickActions {

    fun resolve(
        enabled: Boolean,
        csv: String,
        lockedDown: Boolean,
        navActive: Boolean,
        locked: Boolean,
        lightOn: Boolean,
        recording: Boolean,
    ): List<RideQuickAction> {
        if (lockedDown || !enabled) return emptyList()
        return NotificationActionType.parse(csv).mapNotNull { type ->
            when (type) {
                NotificationActionType.STOP_ALL -> Res.string.notif_btn_stop_all
                NotificationActionType.LOCK ->
                    if (locked) Res.string.notif_btn_unlock else Res.string.notif_btn_lock
                NotificationActionType.LIGHT ->
                    if (lightOn) Res.string.notif_btn_light_off else Res.string.notif_btn_light_on
                NotificationActionType.RECORD ->
                    if (recording) Res.string.notif_btn_stop_record else Res.string.notif_btn_record
                NotificationActionType.HORN -> Res.string.notif_btn_horn
                NotificationActionType.DISCONNECT -> Res.string.notif_btn_disconnect
                NotificationActionType.STOP_NAV ->
                    if (navActive) Res.string.notif_btn_stop_nav else null
            }?.let { RideQuickAction(type, it) }
        }
    }

    /**
     * The ActionExecutor key a press runs, for the buttons the executor knows.
     * Record follows the state the button showed (start or stop), as
     * Android's two intents do. Null for Stop all, Disconnect and Stop
     * navigation, which stop the app's own services rather than act on the
     * wheel, so each platform runs them itself.
     */
    fun executorKey(type: NotificationActionType, recording: Boolean): String? = when (type) {
        NotificationActionType.LOCK -> "LOCK_TOGGLE"
        NotificationActionType.LIGHT -> "LIGHT_TOGGLE"
        NotificationActionType.HORN -> "HORN"
        NotificationActionType.RECORD -> if (recording) "RECORD_STOP" else "RECORD_START"
        NotificationActionType.STOP_ALL,
        NotificationActionType.DISCONNECT,
        NotificationActionType.STOP_NAV -> null
    }
}
