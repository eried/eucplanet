package com.eried.eucplanet.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wheel reconnect hunt on a pretend clock: the same delays, windows,
 * pauses and conditions Android's BleConnectionManager uses, as the iPhone
 * runs them.
 */
class WheelReconnectHuntTest {
    private var now = 1_000_000L
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()

    private inner class Radio : HuntRadio {
        override var adapterOn = true
        override var state = ConnectionState.DISCONNECTED
        val scans = mutableListOf<Pair<String, Boolean>>()
        var scanStops = 0
        val connects = mutableListOf<String>()
        var canScan = true
        override fun startHuntScan(address: String, lowPower: Boolean): Boolean {
            if (!canScan) return false
            scans += address to lowPower
            return true
        }
        override fun stopHuntScan() { scanStops++ }
        override fun connect(address: String, name: String?) {
            connects += address
            hunt.connectRequested(address, name, isAuto = true, virtual = false)
            state = ConnectionState.CONNECTING
        }
    }

    private val radio = Radio()
    private val hunt = WheelReconnectHunt(radio, clock = { now }) { delay, block -> pending += (now + delay) to block }

    /** Moves the clock on by [ms], running every timer that falls due, in order. */
    private fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = pending.filter { it.first <= end }.minByOrNull { it.first } ?: break
            pending.remove(next)
            now = next.first
            next.second()
        }
        now = end
    }

    private fun connectedTo(address: String, isAuto: Boolean = false) {
        assertTrue(hunt.connectRequested(address, "Master_1234", isAuto, virtual = false))
        radio.state = ConnectionState.CONNECTED
    }

    private fun drop(unexpected: Boolean = true) {
        radio.state = ConnectionState.DISCONNECTED
        hunt.dropped(unexpected)
    }

    @Test fun theNumbersAreAndroids() {
        assertEquals(2_000L, WheelReconnect.DROP_DELAY_MS)
        assertEquals(1_500L, WheelReconnect.BLUETOOTH_ON_SETTLE_MS)
        assertEquals(60_000L, WheelReconnect.SCAN_WINDOW_MS)
        assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L), (0..5).map { WheelReconnect.pauseAfterWindow(it) }.take(4))
        assertEquals(60_000L, WheelReconnect.pauseAfterWindow(9))
        assertFalse(WheelReconnect.lowPowerDue(10 * 60_000L - 1, 0))
        assertTrue(WheelReconnect.lowPowerDue(10 * 60_000L, 0))
        assertEquals(1_000L, WheelReconnect.RSSI_POLL_MS)
    }

    @Test fun aDroppedWheelIsHuntedAfterTwoSecondsAndReconnectedWhenSeen() {
        connectedTo("W1")
        drop()
        advance(1_999)
        assertTrue(radio.scans.isEmpty(), "not before the wheel can advertise again")
        advance(1)
        assertEquals(listOf("W1" to false), radio.scans)
        assertEquals(ConnectionState.SCANNING, radio.state, "the dashboard shows it searching, not Disconnected")
        hunt.seen("someone else")
        assertTrue(radio.connects.isEmpty())
        hunt.seen("W1")
        assertEquals(listOf("W1"), radio.connects)
        assertTrue(hunt.connectIsAuto)
        assertFalse(hunt.scanning)
    }

    @Test fun aDisconnectTheRiderAskedForIsNotChased() {
        connectedTo("W1")
        hunt.disconnectRequested()
        drop(unexpected = false)
        advance(10 * 60_000L)
        assertTrue(radio.scans.isEmpty())
        assertEquals(ConnectionState.DISCONNECTED, radio.state)
        hunt.bluetoothOn()
        advance(5_000)
        assertTrue(radio.scans.isEmpty(), "nor brought back when Bluetooth returns")
    }

    @Test fun aCleanDropIsNotChased() {
        connectedTo("W1")
        drop(unexpected = false)
        advance(60_000)
        assertTrue(radio.scans.isEmpty())
    }

    @Test fun aVirtualWheelIsNeverHunted() {
        hunt.connectRequested("VIRTUAL:MASTER", null, isAuto = false, virtual = true)
        radio.state = ConnectionState.CONNECTED
        drop()
        advance(60_000)
        assertTrue(radio.scans.isEmpty())
    }

    @Test fun windowsCloseToDisconnectedAndReopenAfterGrowingPauses() {
        connectedTo("W1")
        drop()
        advance(2_000)
        val starts = mutableListOf<Long>()
        var seenScans = 0
        repeat(6) {
            while (radio.scans.size == seenScans) advance(1_000)
            seenScans = radio.scans.size
            starts += now
            advance(60_000)
            assertEquals(ConnectionState.DISCONNECTED, radio.state, "between windows the wheel reads Disconnected")
        }
        val gaps = starts.zipWithNext { a, b -> b - a - 60_000 }
        assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L, 60_000L), gaps)
    }

    @Test fun aLongHuntDropsToLowPowerAndTheAppComingBackRestartsItAtFullSpeed() {
        connectedTo("W1")
        drop()
        advance(2_000)
        val huntStart = now
        while (!radio.scans.last().second && now - huntStart < 20 * 60_000L) advance(1_000)
        assertTrue(radio.scans.last().second, "a window opening after ten minutes scans on low power")
        assertTrue(now - huntStart >= 10 * 60_000L)
        assertTrue(radio.scans.dropLast(1).none { it.second }, "full speed before that")
        // Between windows: the app opening rescans at full speed and starts the hunt over.
        while (radio.state != ConnectionState.DISCONNECTED) advance(1_000)
        hunt.foreground()
        assertEquals(false, radio.scans.last().second)
        assertEquals(0, hunt.cycle)
    }

    @Test fun theScanScreenHoldsTheHuntAndDropsOnlyAnAutomaticConnection() {
        connectedTo("W1")
        assertFalse(hunt.suppress(), "a wheel the rider tapped stays connected")
        hunt.resume()
        drop()
        assertFalse(hunt.suppress())
        advance(30_000)
        assertTrue(radio.scans.isEmpty(), "held while the rider picks a wheel")
        assertFalse(hunt.connectRequested("W1", null, isAuto = true, virtual = false), "automatic connects are held")
        hunt.resume()
        advance(300)
        assertEquals(listOf("W1" to false), radio.scans)
        hunt.seen("W1")
        radio.state = ConnectionState.CONNECTED
        assertTrue(hunt.suppress(), "a reconnected wheel is the app's, so the scan screen drops it")
    }

    @Test fun bluetoothComingBackStartsTheHuntAfterItSettles() {
        connectedTo("W1")
        radio.adapterOn = false
        hunt.bluetoothOff()
        radio.state = ConnectionState.DISCONNECTED
        radio.adapterOn = true
        hunt.bluetoothOn()
        advance(1_499)
        assertTrue(radio.scans.isEmpty())
        advance(1)
        assertEquals(listOf("W1" to false), radio.scans)
    }

    @Test fun anotherWheelPickedMeanwhileIsLeftAlone() {
        connectedTo("W1")
        drop()
        connectedTo("W2")
        advance(5_000)
        assertTrue(radio.scans.isEmpty())
    }

    @Test fun noScannerMeansAStraightConnect() {
        radio.canScan = false
        connectedTo("W1")
        drop()
        advance(2_000)
        assertEquals(listOf("W1"), radio.connects)
    }

    @Test fun aWaitingConnectThatLandsEndsTheHunt() {
        connectedTo("W1")
        drop()
        advance(2_000)
        radio.state = ConnectionState.CONNECTING
        hunt.reconnected()
        assertFalse(hunt.scanning)
        assertEquals(0, hunt.cycle)
        assertTrue(hunt.connectIsAuto)
        radio.state = ConnectionState.CONNECTED
        advance(120_000)
        assertEquals(1, radio.scans.size, "no window reopens on a live link")
    }
}
