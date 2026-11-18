package com.eried.eucplanet.ble.virtual

import com.eried.eucplanet.ble.BegodeModel
import com.eried.eucplanet.ble.BegodeParser
import com.eried.eucplanet.ble.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sagging Master draws from the pack: negative current on the Begode wire, as on the real wheel. */
class BegodeMasterSaggingCurrentTest {

    private fun decode(elapsedMs: Long): com.eried.eucplanet.data.model.WheelData {
        val sim = BegodeMasterSaggingVirtualWheel()
        val parser = BegodeParser()
        val frames = sim.onTick(elapsedMs)
        val results = frames.flatMap { parser.feed(it, BegodeModel.MASTER) }
        return results.filterIsInstance<DecodeResult.Telemetry>().last().data
    }

    @Test fun `on the throttle the current is negative`() {
        // 5 s into the 30 s cycle: full load, 25 km/h, 60 A.
        val d = decode(5_000L)
        assertEquals(25f, d.speed, 0.1f)
        assertTrue("current ${d.current}", d.current < 0f)
        assertEquals(-60f, d.current, 0.01f)
    }

    @Test fun `coasting still draws, so the current stays negative`() {
        // 25 s into the cycle: coasting at 15 km/h, 2 A.
        val d = decode(25_000L)
        assertEquals(15f, d.speed, 0.1f)
        assertTrue("current ${d.current}", d.current < 0f)
    }
}
