package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.NotificationActionType
import com.eried.eucplanet.data.model.NotificationActionType.DISCONNECT
import com.eried.eucplanet.data.model.NotificationActionType.HORN
import com.eried.eucplanet.data.model.NotificationActionType.LIGHT
import com.eried.eucplanet.data.model.NotificationActionType.LOCK
import com.eried.eucplanet.data.model.NotificationActionType.RECORD
import com.eried.eucplanet.data.model.NotificationActionType.STOP_ALL
import com.eried.eucplanet.data.model.NotificationActionType.STOP_NAV
import com.eried.eucplanet.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ride surface's quick-action buttons, as Android's notification and the
 * iPhone's Live Activity both build them.
 */
class RideQuickActionsTest {

    private fun resolve(
        csv: String = NotificationActionType.DEFAULT_KEYS,
        enabled: Boolean = true,
        lockedDown: Boolean = false,
        navActive: Boolean = false,
        locked: Boolean = false,
        lightOn: Boolean = false,
        recording: Boolean = false,
    ) = RideQuickActions.resolve(enabled, csv, lockedDown, navActive, locked, lightOn, recording)

    @Test
    fun theRidersPicksInSlotOrder() {
        assertEquals(listOf(HORN, LIGHT, RECORD), resolve("HORN,LIGHT,RECORD").map { it.type })
        // A picked-twice action shows twice; an empty slot shows nothing.
        assertEquals(listOf(LOCK, LOCK), resolve("LOCK,NONE,LOCK").map { it.type })
    }

    @Test
    fun switchedOffOrLockedDownShowsNoButtons() {
        assertTrue(resolve(enabled = false).isEmpty())
        assertTrue(resolve(lockedDown = true).isEmpty())
    }

    @Test
    fun stopNavigationOnlyWhileNavigating() {
        assertEquals(listOf(STOP_ALL, LOCK), resolve().map { it.type })
        assertEquals(listOf(STOP_ALL, LOCK, STOP_NAV), resolve(navActive = true).map { it.type })
    }

    @Test
    fun labelsFollowTheState() {
        val csv = "LOCK,LIGHT,RECORD"
        assertEquals(
            listOf(Res.string.notif_btn_lock, Res.string.notif_btn_light_on, Res.string.notif_btn_record),
            resolve(csv).map { it.label },
        )
        assertEquals(
            listOf(Res.string.notif_btn_unlock, Res.string.notif_btn_light_off, Res.string.notif_btn_stop_record),
            resolve(csv, locked = true, lightOn = true, recording = true).map { it.label },
        )
        assertEquals(
            listOf(Res.string.notif_btn_stop_all, Res.string.notif_btn_horn, Res.string.notif_btn_disconnect),
            resolve("STOP_ALL,HORN,DISCONNECT").map { it.label },
        )
    }

    @Test
    fun pressesRunTheExecutorsKeys() {
        assertEquals("LOCK_TOGGLE", RideQuickActions.executorKey(LOCK, false))
        assertEquals("LIGHT_TOGGLE", RideQuickActions.executorKey(LIGHT, false))
        assertEquals("HORN", RideQuickActions.executorKey(HORN, false))
        assertEquals("RECORD_START", RideQuickActions.executorKey(RECORD, recording = false))
        assertEquals("RECORD_STOP", RideQuickActions.executorKey(RECORD, recording = true))
        assertNull(RideQuickActions.executorKey(STOP_ALL, false))
        assertNull(RideQuickActions.executorKey(DISCONNECT, false))
        assertNull(RideQuickActions.executorKey(STOP_NAV, false))
    }
}
