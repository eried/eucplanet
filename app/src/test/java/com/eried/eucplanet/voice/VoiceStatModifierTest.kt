package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceStatModifier.Stat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "max speed" is the speed metric with a statistic asked of it.
 *
 * Peeling the modifier off before matching is what lets fifty metrics gain
 * four statistics without the vocabulary growing to two hundred names, and
 * without giving the matcher four new ways to be ambiguous about each one.
 */
class VoiceStatModifierTest {

    private val phrases = mapOf(
        Stat.MAX to "max,maximum,highest",
        Stat.MIN to "min,minimum,lowest",
        Stat.AVG to "average,avg,mean",
        Stat.PEAK to "peak,sustained",
    )

    private fun parse(s: String) = VoiceStatModifier.parse(s, phrases)

    @Test
    fun `the modifier comes off and the metric is left`() {
        assertEquals(Stat.MAX, parse("max speed").stat)
        assertEquals("speed", parse("max speed").rest)
        assertEquals(Stat.AVG, parse("average speed").stat)
        assertEquals("speed", parse("average speed").rest)
    }

    @Test
    fun `it works inside a question`() {
        // A rider says a sentence, not a keyword.
        val p = parse("what was my max speed")
        assertEquals(Stat.MAX, p.stat)
        assertEquals("what was my speed", p.rest)
    }

    @Test
    fun `every phrasing of a statistic is accepted`() {
        for (word in listOf("max", "maximum", "highest")) {
            assertEquals("\"$word\" was not read as MAX", Stat.MAX, parse("$word pwm").stat)
        }
    }

    @Test
    fun `no modifier leaves the phrase untouched`() {
        val p = parse("what is my speed")
        assertNull(p.stat)
        assertEquals("what is my speed", p.rest)
    }

    @Test
    fun `a modifier on its own is not a question`() {
        // "max" alone leaves nothing to match, and guessing which of fifty
        // metrics was meant would be worse than admitting it was not heard.
        assertEquals("", parse("max").rest)
    }

    @Test
    fun `a metric whose own name contains a modifier word still resolves`() {
        // "Trip max" is a metric, not a statistic request. The modifier is
        // removed, leaving "trip", which is a metric of its own: the honest
        // limit of peeling words off, and worth knowing rather than
        // discovering on a wheel.
        val p = parse("trip max")
        assertEquals(Stat.MAX, p.stat)
        assertEquals("trip", p.rest)
    }

    @Test
    fun `multi-word phrasings are matched as a sequence`() {
        val p = VoiceStatModifier.parse("lowest ever pwm", mapOf(Stat.MIN to "lowest ever"))
        assertEquals(Stat.MIN, p.stat)
        assertEquals("pwm", p.rest)
    }
}
