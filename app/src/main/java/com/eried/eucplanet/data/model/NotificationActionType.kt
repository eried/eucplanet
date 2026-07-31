package com.eried.eucplanet.data.model

import androidx.annotation.StringRes
import com.eried.eucplanet.R

/**
 * The quick actions a rider can put on the ongoing notification. The General
 * settings "Customize actions" picker offers [SLOTS] independent slots, each a
 * dropdown over these entries (or "None") - exactly like the Flic button
 * pickers, so the same action may be picked more than once (harmless, it just
 * shows twice). WheelService.buildNotification turns the chosen keys into
 * notification buttons with context-aware labels (Lock/Unlock, Light on/off,
 * Record/Stop).
 *
 * This is the notification surface's own action list rather than the shared
 * [ActionCatalog] because it carries app/service-control actions the catalog
 * deliberately omits (STOP_ALL, STOP_NAV, DISCONNECT) and renders context-aware
 * labels the catalog's single labels can't express.
 *
 * [STOP_NAV] only appears while navigation is running - Android has no way to
 * grey out a notification action, so a not-applicable one is hidden instead.
 * That means when it is one of the picks, the rider sees fewer buttons while
 * not navigating; that is expected.
 */
enum class NotificationActionType(val key: String, @StringRes val pickerLabel: Int) {
    STOP_ALL("STOP_ALL", R.string.notif_action_stop_all),
    LOCK("LOCK", R.string.notif_action_lock),
    LIGHT("LIGHT", R.string.notif_action_light),
    RECORD("RECORD", R.string.notif_action_record),
    HORN("HORN", R.string.notif_action_horn),
    DISCONNECT("DISCONNECT", R.string.notif_action_disconnect),
    STOP_NAV("STOP_NAV", R.string.notif_action_stop_nav);

    companion object {
        /** Most Android notifications reliably show only three collapsed actions. */
        const val SLOTS = 3
        /** Synthetic "empty slot" key, mirrors the Flic picker's "NONE". */
        const val NONE = "NONE"
        const val DEFAULT_KEYS = "STOP_ALL,LOCK,STOP_NAV"

        fun byKey(key: String): NotificationActionType? = entries.firstOrNull { it.key == key }

        /**
         * The stored CSV as exactly [SLOTS] ordered slot keys - each an action
         * key or [NONE] - padding short input and truncating long input. Blank
         * entries become [NONE] so the slot's position is preserved.
         */
        fun slots(csv: String): List<String> {
            val parts = csv.split(",").map { it.trim() }
            return (0 until SLOTS).map { i -> parts.getOrNull(i)?.ifEmpty { NONE } ?: NONE }
        }

        /**
         * Effective actions in slot order, dropping empty/unknown slots.
         * Duplicates are kept on purpose - picking one action twice is allowed.
         */
        fun parse(csv: String): List<NotificationActionType> =
            slots(csv).mapNotNull { byKey(it) }
    }
}
