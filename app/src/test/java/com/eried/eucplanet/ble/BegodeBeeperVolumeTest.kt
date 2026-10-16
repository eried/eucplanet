package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.sanitized
import com.eried.eucplanet.service.HeadlightSlowPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Beeper volume by speed on a Begode: what is written to the wheel, that it
 * reaches the wheel through the composite adapter, and when it switches.
 */
class BegodeBeeperVolumeTest {

    @Test fun `the volume is W, B and the digit, with no confirm beep`() {
        val frames = BegodeAdapter().setBeeperVolume(5)!!
        assertEquals(3, frames.size)
        assertArrayEquals(byteArrayOf('W'.code.toByte()), frames[0])
        assertArrayEquals(byteArrayOf('B'.code.toByte()), frames[1])
        assertArrayEquals(byteArrayOf('5'.code.toByte()), frames[2])
        // The spec's trailing `b` would beep at every switch.
        assertFalse(frames.any { it.contentEquals(byteArrayOf('b'.code.toByte())) })
    }

    @Test fun `out of range sends nothing`() {
        assertNull(BegodeAdapter().setBeeperVolume(0))
        assertNull(BegodeAdapter().setBeeperVolume(10))
    }

    @Test fun `it reaches a Begode through the composite, and only a Begode`() {
        fun composite(name: String) = CompositeWheelAdapter(
            InMotionV2Adapter(), InMotionV1Adapter(), KingsongAdapter(),
            BegodeAdapter(), VeteranAdapter(), NinebotAdapter(),
        ).apply { notifyConnectingTo(name) }
        val begode = composite("Begode Master")
        assertTrue(begode.capabilities.hasBeeperVolume)
        assertEquals(3, begode.setBeeperVolume(9)?.size)
        val kingsong = composite("KS-S22-1234")
        assertFalse(kingsong.capabilities.hasBeeperVolume)
        assertNull(kingsong.setBeeperVolume(9))
    }

    @Test fun `with no hold it is quiet the moment speed drops under the threshold`() {
        val riding = HeadlightSlowPolicy.State()
        val slowing = HeadlightSlowPolicy.step(riding, 4.9f, 5f, nowMs = 1000L, enabled = true, holdMs = 0L)
        assertTrue(slowing.forcedOff)
        // And it needs real riding, 2 km/h over, to go loud again.
        val hover = HeadlightSlowPolicy.step(slowing, 6f, 5f, nowMs = 2000L, enabled = true, holdMs = 0L)
        assertTrue(hover.forcedOff)
        val away = HeadlightSlowPolicy.step(hover, 7f, 5f, nowMs = 3000L, enabled = true, holdMs = 0L)
        assertFalse(away.forcedOff)
    }

    @Test fun `stored volumes outside 1 to 9 are clamped`() {
        val s = AppSettings().let { it.copy(horn = it.horn.copy(beepVolumeStopped = 0, beepVolumeRiding = 42)) }
            .sanitized()
        assertEquals(1, s.horn.beepVolumeStopped)
        assertEquals(9, s.horn.beepVolumeRiding)
    }
}
