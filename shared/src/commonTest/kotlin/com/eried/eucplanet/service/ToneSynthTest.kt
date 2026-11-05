package com.eried.eucplanet.service

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shape of a rendered beep, on every platform the synth runs on. */
class ToneSynthTest {
    private val rate = ToneSynth.SAMPLE_RATE

    @Test fun `a beep is lead pad then tone then tail pad and never louder than the headroom`() {
        val r = ToneSynth.renderBeep(1000, 100, 1, 0, 100, -1, 0, 0, 0, 30, 0)!!
        val lead = rate * 30 / 1000
        val tone = rate * 100 / 1000
        assertEquals(lead + tone + rate * 12 / 1000, r.frames)
        assertTrue(r.samples.take(lead).all { it == 0.toShort() })
        val peak = r.samples.maxOf { abs(it.toInt()) }
        assertTrue(peak <= (0.8 * Short.MAX_VALUE).toInt() + 1, "peak $peak")
        assertTrue(peak > Short.MAX_VALUE / 2, "peak $peak")
    }

    @Test fun `a short beep is padded to the output minimum`() {
        val r = ToneSynth.renderBeep(1000, 10, 1, 0, 100, -1, 0, 0, 0, 0, 8192)!!
        assertEquals(8192, r.frames)
    }

    @Test fun `separate beeps leave silent gaps`() {
        val r = ToneSynth.renderBeep(1000, 50, 2, 50, 100, -1, 0, 0, 0, 0, 0)!!
        val tone = rate * 50 / 1000
        assertTrue(r.samples.slice(tone until 2 * tone).all { it == 0.toShort() })
    }

    @Test fun `an empty tone renders nothing`() {
        assertNull(ToneSynth.renderBeep(1000, 0, 1, 0, 100, -1, 0, 0, 0, 0, 0))
    }
}
