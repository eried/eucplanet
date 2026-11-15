package com.eried.eucplanet.ble.gps

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.ExternalGpsSample
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The virtual RaceBox (Service Mode) streams what a real one does: its frames
 * go through the real parser, start where the position assist put them, and
 * ride at the wheel's speed less the offset the calibration should find.
 * The router hands a real box straight to the phone's link.
 */
class VirtualRaceBoxTest {

    private fun decodeAll(adapter: RaceBoxAdapter, frame: ByteArray): ExternalGpsSample? {
        var out: ExternalGpsSample? = null
        for (piece in VirtualRaceBox.chunks(frame)) adapter.decode(piece)?.let { out = it }
        return out
    }

    @Test fun `its frames decode through the real parser`() {
        val box = VirtualRaceBox()
        val adapter = RaceBoxAdapter()
        val s = decodeAll(adapter, box.frame(1_000_000L, 0L, null))
        assertNotNull(s)
        assertEquals(12, s.numSatellites)
        assertEquals(VirtualRaceBox.BATTERY_PCT, s.batteryPercent)
        assertEquals(false, s.charging)
        assertEquals(VirtualRaceBox.DEFAULT_LAT, s.latitude, 1e-6)
        assertEquals(0.8f, s.accuracyMeters, 0.001f)
        assertNotNull(s.accelYG, "the Data Message carries the G-force")
    }

    @Test fun `it starts where the position assist says`() {
        val box = VirtualRaceBox()
        box.onInitWrites(RaceBoxAdapter().initCommands(0L, 41.4, 2.17, 10f))
        val s = decodeAll(RaceBoxAdapter(), box.frame(1_000L, 0L, null))!!
        assertEquals(41.4, s.latitude, 1e-6)
        assertEquals(2.17, s.longitude, 1e-6)
    }

    @Test fun `with a wheel it rides at the wheel speed less three percent`() {
        val box = VirtualRaceBox()
        val s = decodeAll(RaceBoxAdapter(), box.frame(1_000L, 0L, 30f))!!
        assertEquals(29.1f, s.speedKmh, 0.01f)
    }

    @Test fun `alone it loops from standstill to 30 kmh and back`() {
        val box = VirtualRaceBox()
        assertEquals(0f, box.speedKmh(0L, null), 0.01f)
        assertEquals(30f, box.speedKmh(VirtualRaceBox.LOOP_MS / 2, null), 0.01f)
        assertEquals(0f, box.speedKmh(VirtualRaceBox.LOOP_MS, null), 0.01f)
    }

    @Test fun `it moves along its heading`() {
        val box = VirtualRaceBox()
        val adapter = RaceBoxAdapter()
        val a = decodeAll(adapter, box.frame(0L, 0L, 36f))!!
        val b = decodeAll(adapter, box.frame(1_000L, 1_000L, 36f))!!
        // 36 km/h less 3% is 9.7 m/s, heading about north.
        val northM = (b.latitude - a.latitude) * 111_320.0
        assertEquals(9.7, northM, 0.2)
    }

    private class FakeLink : ExternalGpsLink {
        val state = MutableStateFlow(ConnectionState.DISCONNECTED)
        val out = MutableSharedFlow<ExternalGpsSample>(extraBufferCapacity = 4)
        override val connectionState: StateFlow<ConnectionState> = state
        override val samples: SharedFlow<ExternalGpsSample> = out
        val connected = mutableListOf<String>()
        var disconnects = 0
        override fun connect(address: String, adapter: ExternalGpsAdapter, initWrites: List<ByteArray>) {
            connected += address
            state.value = ConnectionState.CONNECTING
        }
        override fun disconnect() {
            disconnects++
            state.value = ConnectionState.DISCONNECTED
        }
    }

    @Test fun `a real box goes to the phone link and its state passes through`() {
        val real = FakeLink()
        val router = ExternalGpsLinkRouter(real, { null })
        router.connect("AA:BB:CC:DD:EE:FF", RaceBoxAdapter(), emptyList())
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), real.connected)
        assertEquals(ConnectionState.CONNECTING, router.connectionState.value)
        real.state.value = ConnectionState.CONNECTED
        assertEquals(ConnectionState.CONNECTED, router.connectionState.value)
        router.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, router.connectionState.value)
    }

    @Test fun `the virtual address streams decoded samples without the phone link`() = runBlocking {
        val real = FakeLink()
        val router = ExternalGpsLinkRouter(real, { 20f }, tickMs = 10L)
        router.connect(VirtualExternalGps.ADDRESS, RaceBoxAdapter(), emptyList())
        assertTrue(real.connected.isEmpty())
        // Real time: the box ticks on its own dispatcher.
        val s = withContext(Dispatchers.Default) { withTimeout(5_000) { router.samples.first() } }
        assertEquals(19.4f, s.speedKmh, 0.01f)
        assertEquals(ConnectionState.CONNECTED, router.connectionState.value)
        router.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, router.connectionState.value)
        // The phone link's state is the router's again.
        real.state.value = ConnectionState.CONNECTING
        assertEquals(ConnectionState.CONNECTING, router.connectionState.value)
    }

    @Test fun `only the pseudo-address is virtual`() {
        assertTrue(VirtualExternalGps.isVirtual(VirtualExternalGps.ADDRESS))
        assertEquals(false, VirtualExternalGps.isVirtual("AA:BB:CC:DD:EE:FF"))
        assertEquals(false, VirtualExternalGps.isVirtual(null))
        assertNull(VirtualExternalGps.scanResult.device.address.takeIf { !it.startsWith("VIRTUAL:") })
    }
}
