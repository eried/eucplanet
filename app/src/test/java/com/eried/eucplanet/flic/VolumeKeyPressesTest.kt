package com.eried.eucplanet.flic

import com.eried.eucplanet.flic.VolumeKeyPresses.Fired
import com.eried.eucplanet.flic.VolumeKeyPresses.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeKeyPressesTest {

    @Test
    fun `a press and a release is one click`() {
        val p = VolumeKeyPresses()
        assertEquals(emptyList<Fired>(), p.adjust(1, 0L))
        assertEquals(listOf(Fired(Key.UP, hold = false)), p.adjust(0, 120L))
        assertNull(p.expiresAt)
    }

    @Test
    fun `a held key that repeats fires one hold at 600 ms and no click on release`() {
        val p = VolumeKeyPresses()
        assertEquals(emptyList<Fired>(), p.adjust(1, 0L))
        assertEquals(emptyList<Fired>(), p.adjust(1, 400L))
        assertEquals(listOf(Fired(Key.UP, hold = true)), p.adjust(1, 650L))
        assertEquals(emptyList<Fired>(), p.adjust(1, 700L))
        assertEquals(emptyList<Fired>(), p.adjust(0, 900L))
    }

    @Test
    fun `a phone that does not repeat still gets a hold from the release time`() {
        val p = VolumeKeyPresses()
        p.adjust(-1, 0L)
        assertEquals(listOf(Fired(Key.DOWN, hold = true)), p.adjust(0, 900L))
    }

    @Test
    fun `a release that never comes ends the press as a click after the timeout`() {
        val p = VolumeKeyPresses()
        p.adjust(1, 0L)
        assertEquals(1500L, p.expiresAt)
        assertNull(p.expire(1000L))
        assertEquals(Fired(Key.UP, hold = false), p.expire(1600L))
        assertNull(p.expiresAt)
        assertEquals(emptyList<Fired>(), p.adjust(0, 1700L))
    }

    @Test
    fun `the other key arriving releases the first`() {
        val p = VolumeKeyPresses()
        p.adjust(1, 0L)
        assertEquals(listOf(Fired(Key.UP, hold = false)), p.adjust(-1, 100L))
        assertEquals(listOf(Fired(Key.DOWN, hold = false)), p.adjust(0, 200L))
    }

    @Test
    fun `the timeout follows the last repeat, not the first press`() {
        val p = VolumeKeyPresses()
        p.adjust(1, 0L)
        p.adjust(1, 1400L)
        assertEquals(2900L, p.expiresAt)
    }
}
