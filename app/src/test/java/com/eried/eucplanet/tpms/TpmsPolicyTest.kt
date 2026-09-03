package com.eried.eucplanet.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a second pressure source creates.
 *
 * With one source there was nothing to decide: the P6 relayed a number and
 * that was the number. A paired sensor makes "which one" and "is it still
 * true" into real questions, and both are answered here rather than on a wheel
 * in a car park.
 */
class TpmsPolicyTest {

    private val t0 = 1_000_000L
    private fun wheel(kpa: Float, at: Long = t0) = TpmsReading(kpa, TpmsSource.WHEEL, at)
    private fun paired(kpa: Float, at: Long = t0) = TpmsReading(kpa, TpmsSource.PAIRED, at)

    @Test fun `a paired sensor replaces the wheel's, which is what the settings row promises`() {
        val picked = TpmsPolicy.pick(paired(240f), wheel(200f), t0)
        assertEquals(TpmsSource.PAIRED, picked?.source)
        assertEquals(240f, picked?.kpa)
    }

    @Test fun `with no paired sensor the wheel's own is the answer`() {
        val picked = TpmsPolicy.pick(null, wheel(200f), t0)
        assertEquals(TpmsSource.WHEEL, picked?.source)
    }

    @Test fun `a quiet paired sensor goes silent rather than falling back`() {
        // The rider replaced the wheel's sensor deliberately. Swapping back
        // when theirs goes quiet would show a different tyre under the same
        // label, with nothing saying it changed.
        val old = paired(240f, t0)
        val now = t0 + TpmsPolicy.STALE_AFTER_MS
        assertNull(TpmsPolicy.pick(old, wheel(200f, now), now))
    }

    @Test fun `unpairing does fall back, because the rider asked for it`() {
        val now = t0 + TpmsPolicy.STALE_AFTER_MS
        val picked = TpmsPolicy.pick(null, wheel(200f, now), now)
        assertEquals(TpmsSource.WHEEL, picked?.source)
    }

    @Test fun `a reading stops being shown once nothing is behind it`() {
        val r = wheel(200f, t0)
        assertTrue(!TpmsPolicy.isStale(r, t0 + TpmsPolicy.STALE_AFTER_MS - 1))
        assertTrue(TpmsPolicy.isStale(r, t0 + TpmsPolicy.STALE_AFTER_MS))
        assertNull(TpmsPolicy.pick(null, r, t0 + TpmsPolicy.STALE_AFTER_MS))
    }

    @Test fun `the window survives a parked spell but not a dead battery`() {
        // Sensors slow down when the wheel is parked, so minutes of quiet are
        // normal; a battery that died overnight is not.
        val parked = 5 * 60 * 1000L
        val overnight = 8 * 60 * 60 * 1000L
        assertTrue(!TpmsPolicy.isStale(wheel(200f), t0 + parked))
        assertTrue(TpmsPolicy.isStale(wheel(200f), t0 + overnight))
    }

    @Test fun `zero from a wheel is no sensor`() {
        // Every family that does not report pressure leaves the field at zero.
        assertNull(TpmsPolicy.readingOf(0f, TpmsSource.WHEEL, t0))
        assertEquals(200f, TpmsPolicy.readingOf(200f, TpmsSource.WHEEL, t0)?.kpa)
    }

    @Test fun `zero from a paired cap is a flat tyre, and must be shown`() {
        // The cap is screwed to a valve and reports what it measures. The
        // rider's own sensor read exactly 0 kPa against a gauge at 0 bar,
        // which is how its format was decoded. Throwing that away made a flat
        // tyre look like a broken sensor: the row went back to saying the
        // readings were not decoded, at the one moment a tyre sensor matters.
        assertEquals(0f, TpmsPolicy.readingOf(0f, TpmsSource.PAIRED, t0)?.kpa)
    }

    @Test fun `a flat tyre survives the repository too`() {
        val repo = TpmsRepository()
        repo.submitPaired(240f, "5B:61:1B:11:11:11", t0)
        repo.submitPaired(0f, "5B:61:1B:11:11:11", t0 + 1000)
        assertEquals(0f, repo.current.value?.kpa)
    }

    @Test fun `the repository keeps the last good reading when a zero arrives`() {
        // A frame with no sensor bound must not blank a real reading.
        val repo = TpmsRepository()
        repo.submitWheel(200f, t0)
        repo.submitWheel(0f, t0 + 1000)
        assertEquals(200f, repo.current.value?.kpa)
    }

    @Test fun `the repository publishes zero once everything has gone quiet`() {
        val repo = TpmsRepository()
        repo.submitWheel(200f, t0)
        assertEquals(200f, repo.pressureKpa.value)
        repo.refresh(t0 + TpmsPolicy.STALE_AFTER_MS)
        assertEquals(0f, repo.pressureKpa.value)
        assertNull(repo.current.value)
    }

    /** A pairing store that survives being handed to a second repository. */
    private class FakeStore(var saved: List<String> = emptyList()) : TpmsPairingStore {
        override fun load(onLoaded: (List<String>) -> Unit) = onLoaded(saved)
        override fun saveAll(addresses: List<String>) { saved = addresses }
    }

    @Test fun `a paired sensor is still paired after the app restarts`() {
        // It never was: pairing lived in memory, so a rider who found their
        // cap and closed the app had to scan for it again, every time.
        val store = FakeStore()
        TpmsRepository(store).adopt("5B:61:1B:11:11:11")
        assertEquals(listOf("5B:61:1B:11:11:11"), store.saved)

        val afterRestart = TpmsRepository(store)
        assertEquals("5B:61:1B:11:11:11", afterRestart.pairedAddress.value)
    }

    @Test fun `forgetting a sensor outlives the app too`() {
        // Otherwise a sensor the rider deleted is back on the next launch.
        val store = FakeStore(listOf("5B:61:1B:11:11:11"))
        val repo = TpmsRepository(store)
        assertEquals("5B:61:1B:11:11:11", repo.pairedAddress.value)
        repo.forgetPaired(t0)
        assertEquals(emptyList<String>(), store.saved)
        assertNull(TpmsRepository(store).pairedAddress.value)
    }

    @Test fun `a sensor found during startup is not overwritten by the stored one`() {
        // load() answers whenever the settings store gets round to it, which
        // can be after a scan has already adopted something.
        var answer: ((List<String>) -> Unit)? = null
        val slow = object : TpmsPairingStore {
            override fun load(onLoaded: (List<String>) -> Unit) { answer = onLoaded }
            override fun saveAll(addresses: List<String>) = Unit
        }
        val repo = TpmsRepository(slow)
        repo.adopt("AA:BB:CC:DD:EE:FF")
        answer?.invoke(listOf("5B:61:1B:11:11:11"))
        assertEquals("AA:BB:CC:DD:EE:FF", repo.pairedAddress.value)
    }

    @Test fun `a rider with several wheels can add a cap for each`() {
        // One slot dropped the second cap before it could even be listed, so
        // it could not be scanned for and could not be added.
        val store = FakeStore()
        val repo = TpmsRepository(store)
        repo.submitPaired(240f, "5B:61:1B:11:11:11", t0)
        repo.submitPaired(210f, "5B:61:1B:22:22:22", t0)
        assertEquals(2, repo.sensors.value.size)
        assertEquals(240f, repo.sensors.value[0].kpa)
        assertEquals(210f, repo.sensors.value[1].kpa)
        assertEquals(2, store.saved.size)
    }

    @Test fun `removing one cap leaves the other wheels alone`() {
        val repo = TpmsRepository(FakeStore())
        repo.submitPaired(240f, "5B:61:1B:11:11:11", t0)
        repo.submitPaired(210f, "5B:61:1B:22:22:22", t0)
        repo.forget("5B:61:1B:11:11:11")
        assertEquals(listOf("5B:61:1B:22:22:22"), repo.sensors.value.map { it.address })
    }

    @Test fun `a paired cap that has not spoken yet has no reading, and that is not a fault`() {
        // These transmit when the pressure moves and stay quiet on a settled
        // tyre, so a freshly started app legitimately has a sensor and no
        // number. The row says it is waiting rather than blaming the decoder.
        val repo = TpmsRepository(FakeStore(listOf("5B:61:1B:11:11:11")))
        assertEquals(1, repo.sensors.value.size)
        assertNull(repo.sensors.value[0].kpa)
    }

    @Test fun `forgetting a paired sensor hands the wheel's back`() {
        val repo = TpmsRepository()
        repo.submitWheel(200f, t0)
        repo.submitPaired(240f, nowMs = t0)
        assertEquals(TpmsSource.PAIRED, repo.current.value?.source)
        repo.forgetPaired(t0)
        assertEquals(TpmsSource.WHEEL, repo.current.value?.source)
        assertEquals(200f, repo.pressureKpa.value)
    }
}
