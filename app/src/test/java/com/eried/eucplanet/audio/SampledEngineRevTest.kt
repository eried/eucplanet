package com.eried.eucplanet.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sampled engines must respond to the wheel.
 *
 * A rider's report, verbatim: "sampled motor sounds don't respond to the
 * wheel's acceleration and just constantly loop the idle sound. Doesn't
 * happen with synth sounds." The composition player kept every loop at
 * 1.0x and conveyed RPM only by crossfading idle and rev volume - two
 * constant-pitch clips trading loudness do not read as revving. The loops
 * now change rate with RPM, pitch riding along.
 *
 * Second report, after that fix: still stuck at idle. The rate ran 0.9 to
 * 1.4 over the whole rev band and the crossfade only finished at the
 * redline, while an EUC rides in the lower half of the band. The idle loop
 * now doubles its pitch by the redline and the fade is done by 70%.
 */
class SampledEngineRevTest {

    private val comp = File("src/main/java/com/eried/eucplanet/audio/CompositionEnginePlayer.kt").readText()

    @Test fun `the update drives the loop rate from rpm`() {
        val update = comp.substringAfter("fun update(rpmNorm: Float").substringBefore("fun fireDecel")
        assertTrue("the idle loop no longer doubles its pitch over the band", update.contains("0.95f + 1.05f * lastRpmNorm"))
        assertTrue("the rev loop no longer follows rpm", update.contains("0.6f + 0.4f * lastRpmNorm"))
        assertTrue("only one loop revs", update.contains("idle?.setRate(idleRate)") && update.contains("rev?.setRate(revRate)"))
    }

    @Test fun `rate means resampling, not time-stretch`() {
        // setPlaybackSpeed alone is pitch-preserving time-stretch - Sonic's
        // stretch artifacts are why variable speed was abandoned the first
        // time. Matched speed and pitch is plain resampling, the tape effect
        // an engine wants.
        val setRate = comp.substringAfter("fun setRate(").substringBefore("fun release")
        assertTrue(setRate.contains("PlaybackParameters(c, c)"))
    }

    @Test fun `the crossfade stays - rate rides on top of it, not instead of it`() {
        val update = comp.substringAfter("fun update(rpmNorm: Float").substringBefore("fun fireDecel")
        assertTrue("the fade must finish before the redline", update.contains("(lastRpmNorm / 0.7f).coerceAtMost(1f)"))
        assertTrue(update.contains("sqrt(1f - fade)"))
        assertTrue(update.contains("sqrt(fade)"))
    }
}
