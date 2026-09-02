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

    @Test fun `zero is no sensor, not a flat tyre`() {
        // Every family that does not report pressure leaves the field at zero.
        assertNull(TpmsPolicy.readingOf(0f, TpmsSource.WHEEL, t0))
        assertEquals(200f, TpmsPolicy.readingOf(200f, TpmsSource.WHEEL, t0)?.kpa)
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
    private class FakeStore(var saved: String? = null) : TpmsPairingStore {
        override fun load(onLoaded: (String?) -> Unit) = onLoaded(saved)
        override fun save(address: String?) { saved = address }
    }

    @Test fun `a paired sensor is still paired after the app restarts`() {
        // It never was: pairing lived in memory, so a rider who found their
        // cap and closed the app had to scan for it again, every time.
        val store = FakeStore()
        TpmsRepository(store).adopt("5B:61:1B:11:11:11")
        assertEquals("5B:61:1B:11:11:11", store.saved)

        val afterRestart = TpmsRepository(store)
        assertEquals("5B:61:1B:11:11:11", afterRestart.pairedAddress.value)
    }

    @Test fun `forgetting a sensor outlives the app too`() {
        // Otherwise a sensor the rider deleted is back on the next launch.
        val store = FakeStore("5B:61:1B:11:11:11")
        val repo = TpmsRepository(store)
        assertEquals("5B:61:1B:11:11:11", repo.pairedAddress.value)
        repo.forgetPaired(t0)
        assertNull(store.saved)
        assertNull(TpmsRepository(store).pairedAddress.value)
    }

    @Test fun `a sensor found during startup is not overwritten by the stored one`() {
        // load() answers whenever the settings store gets round to it, which
        // can be after a scan has already adopted something.
        var answer: ((String?) -> Unit)? = null
        val slow = object : TpmsPairingStore {
            override fun load(onLoaded: (String?) -> Unit) { answer = onLoaded }
            override fun save(address: String?) = Unit
        }
        val repo = TpmsRepository(slow)
        repo.adopt("AA:BB:CC:DD:EE:FF")
        answer?.invoke("5B:61:1B:11:11:11")
        assertEquals("AA:BB:CC:DD:EE:FF", repo.pairedAddress.value)
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
