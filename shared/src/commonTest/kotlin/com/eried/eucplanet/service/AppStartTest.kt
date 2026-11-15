package com.eried.eucplanet.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iPhone starts its services at launch, with or without a screen (a
 * Bluetooth relaunch has none), and the first screen asks again. One start,
 * whoever comes first, so a screen after a background start finds one wheel
 * link and one recorder rather than two.
 */
class AppStartTest {

    @Test fun theFirstAskStartsAndLaterOnesDoNothing() {
        val runs = mutableListOf<LaunchKind>()
        val once = StartOnce { runs += it }
        assertFalse(once.started)
        assertTrue(once.start(LaunchKind.FOREGROUND))
        assertFalse(once.start(LaunchKind.FOREGROUND))
        assertEquals(listOf(LaunchKind.FOREGROUND), runs)
    }

    @Test fun aScreenAfterABackgroundStartAttachesToIt() {
        val runs = mutableListOf<LaunchKind>()
        val once = StartOnce { runs += it }
        // The relaunch, with no screen.
        assertTrue(once.start(LaunchKind.BLUETOOTH_RELAUNCH))
        // The rider opens the app: the screen asks as a normal launch would.
        assertFalse(once.start(LaunchKind.FOREGROUND))
        assertEquals(listOf(LaunchKind.BLUETOOTH_RELAUNCH), runs)
        assertEquals(LaunchKind.BLUETOOTH_RELAUNCH, once.kind)
    }

    @Test fun aStartThatAsksAgainFromInsideRunsOnce() {
        var runs = 0
        lateinit var once: StartOnce
        once = StartOnce { runs++; once.start(LaunchKind.FOREGROUND) }
        once.start(LaunchKind.FOREGROUND)
        assertEquals(1, runs)
    }

    @Test fun nothingRestoredPicksNothing() {
        assertNull(WheelRestore.pick(emptyList()))
    }

    @Test fun theRestoredWheelIsPickedBackUp() {
        val p = RestoredPeripheral("A-1", "KSN-S22", connected = true)
        assertEquals(p, WheelRestore.pick(listOf(p)))
    }

    @Test fun theRidersLastWheelWinsOverAnother() {
        val other = RestoredPeripheral("B-2", "LK1234", connected = true)
        val last = RestoredPeripheral("a-1", "KSN-S22", connected = false)
        assertEquals(last, WheelRestore.pick(listOf(other, last), lastAddress = "A-1"))
    }

    @Test fun aLiveLinkWinsOverAWaitingConnect() {
        val waiting = RestoredPeripheral("B-2", "LK1234", connected = false)
        val live = RestoredPeripheral("A-1", "KSN-S22", connected = true)
        assertEquals(live, WheelRestore.pick(listOf(waiting, live)))
        assertEquals(waiting, WheelRestore.pick(listOf(waiting)))
    }
}
