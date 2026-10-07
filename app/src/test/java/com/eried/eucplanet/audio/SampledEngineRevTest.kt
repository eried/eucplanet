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
 */
class SampledEngineRevTest {

    private val comp = File("src/main/java/com/eried/eucplanet/audio/CompositionEnginePlayer.kt").readText()

    @Test fun `the update drives the loop rate from rpm`() {
        val update = comp.substringAfter("fun update(rpmNorm: Float").substringBefore("fun fireDecel")
        assertTrue("the rate no longer follows rpm", update.contains("0.9f + 0.5f * lastRpmNorm"))
        assertTrue("only one loop revs", update.contains("idle?.setRate(rate)") && update.contains("rev?.setRate(rate)"))
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
        assertTrue(update.contains("sqrt(1f - lastRpmNorm)"))
        assertTrue(update.contains("sqrt(lastRpmNorm)"))
    }
}
