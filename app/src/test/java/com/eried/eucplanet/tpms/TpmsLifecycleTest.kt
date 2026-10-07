package com.eried.eucplanet.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping that decides whether anything is listening.
 *
 * An audit found several ways to end up with a sensor paired and no radio
 * running, all permanent until the app was killed, and all from the same
 * cause: ending a search and ending the watching were the same call. These
 * pin the repository half of that, which is the half a unit test can reach.
 */
class TpmsLifecycleTest {

    // Wall clock, because forget() and recompute() age readings against the
    // real one. A fake epoch makes every reading look hours stale.
    private val t0 = System.currentTimeMillis()
    private val capA = "5B:61:1B:11:11:11"
    private val capB = "5B:F4:63:22:22:22"

    private class FakeStore(var saved: List<String> = emptyList()) : TpmsPairingStore {
        var loadCallback: ((List<String>) -> Unit)? = null
        override fun load(onLoaded: (List<String>) -> Unit) { loadCallback = onLoaded; onLoaded(saved) }
        override fun saveAll(addresses: List<String>) { saved = addresses }
    }

    @Test fun `a deleted cap stops speaking at once`() {
        // forget() cleared the list but not the last addressless reading, and
        // recompute's fallback then re-elected the deleted cap and kept
        // publishing its pressure - with no staleness tick, forever.
        val repo = TpmsRepository(FakeStore())
        repo.submitPaired(240f, capA, t0)
        assertEquals(240f, repo.pressureKpa.value)
        repo.forget(capA)
        assertEquals("a deleted cap kept publishing", 0f, repo.pressureKpa.value)
        assertNull(repo.current.value)
    }

    @Test fun `deleting one cap leaves the other speaking`() {
        val repo = TpmsRepository(FakeStore())
        repo.submitPaired(240f, capA, t0)
        repo.submitPaired(210f, capB, t0)
        repo.forget(capA)
        assertEquals(listOf(capB), repo.sensors.value.map { it.address })
        assertEquals(210f, repo.pressureKpa.value)
    }

    @Test fun `a stored pairing is not lost to a scan that adopts first`() {
        // The settings read lands on an IO thread. A scan can adopt before it
        // returns, and the old rule ("apply only if the list is empty") threw
        // the stored cap away and then saved the shorter list over it.
        val store = FakeStore(listOf(capA))
        val repo = TpmsRepository(store)
        repo.adopt(capB)
        store.loadCallback?.invoke(listOf(capA))
        val held = repo.sensors.value.map { it.address }
        assertTrue("stored cap $capA was dropped: $held", capA in held)
        assertTrue(capB in held)
    }

    @Test fun `loading twice does not duplicate a cap`() {
        val store = FakeStore(listOf(capA))
        val repo = TpmsRepository(store)
        store.loadCallback?.invoke(listOf(capA))
        assertEquals(1, repo.sensors.value.count { it.address == capA })
    }

    @Test fun `a reading ages out once nothing is reporting`() {
        // Nothing called refresh() in production, so staleness was written and
        // tested and never actually evaluated after the last packet: a dead
        // cap kept its number and its green dot for good.
        val repo = TpmsRepository(FakeStore())
        repo.submitPaired(240f, capA, t0)
        assertEquals(240f, repo.pressureKpa.value)
        repo.refresh(t0 + TpmsPolicy.PAIRED_STALE_AFTER_MS)
        assertEquals(0f, repo.pressureKpa.value)
        assertNull(repo.activeAddress.value)
    }
}
