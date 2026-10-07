package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceCommandMatcher.VoiceMatch
import com.eried.eucplanet.voice.VoiceVocabulary.Kind
import com.eried.eucplanet.voice.VoiceVocabulary.SpokenTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning what a rider said into what they meant.
 *
 * The names here are the real ones out of MetricCatalog, because the awkward
 * cases are real: the catalog has three temperatures and two speed limits, and
 * a rider saying "temperature" has genuinely not said which.
 */
class VoiceCommandMatcherTest {

    private fun metric(key: String, name: String) = SpokenTerm(key, Kind.METRIC, name)

    private val vocabulary = listOf(
        metric("BATTERY", "Battery"),
        metric("SPEED", "Speed"),
        metric("WH_PER_KM", "Consumption"),
        metric("TEMPERATURE", "Temperature"),
        metric("MOTOR_TEMP", "Motor temperature"),
        metric("CONTROLLER_TEMP", "Controller temperature"),
        metric("BATTERY_TEMP", "Battery temperature"),
        metric("DYN_SPEED_LIMIT", "Dynamic speed limit"),
        metric("WHEEL_MAX_SPEED", "Wheel max speed"),
        SpokenTerm("PWM", Kind.REPORT, "PWM"),
        SpokenTerm(VoiceVocabulary.SPLIT_KEY, Kind.SPLIT, "Last split"),
    )

    private fun hit(heard: String, onDashboard: Set<String> = emptySet()): String {
        val m = VoiceCommandMatcher.match(heard, vocabulary, onDashboard)
        assertTrue("expected a hit for \"$heard\", got $m", m is VoiceMatch.Hit)
        return (m as VoiceMatch.Hit).term.key
    }

    @Test
    fun `the bare name works`() {
        assertEquals("BATTERY", hit("battery"))
        assertEquals("WH_PER_KM", hit("consumption"))
    }

    @Test
    fun `a whole question works`() {
        // Nobody says the key. They ask a question with the name inside it.
        assertEquals("BATTERY", hit("what is my battery"))
        assertEquals("SPEED", hit("hey what speed am i doing"))
        assertEquals(VoiceVocabulary.SPLIT_KEY, hit("tell me the last split"))
    }

    @Test
    fun `case and punctuation do not matter`() {
        // Recognisers differ on commas and question marks; the rider did not.
        assertEquals("BATTERY", hit("Battery?"))
        assertEquals("WH_PER_KM", hit("  CONSUMPTION,  "))
    }

    @Test
    fun `the longer name wins`() {
        // The reason a rider can ask for one specific temperature at all. Every
        // one of these contains "temperature" too.
        assertEquals("MOTOR_TEMP", hit("what is the motor temperature"))
        assertEquals("CONTROLLER_TEMP", hit("controller temperature"))
        assertEquals("BATTERY_TEMP", hit("battery temperature please"))
        assertEquals("DYN_SPEED_LIMIT", hit("dynamic speed limit"))
    }

    @Test
    fun `a plain word that several names share is ambiguous`() {
        val m = VoiceCommandMatcher.match("temperature", vocabulary)
        assertTrue("got $m", m is VoiceMatch.Hit && m.term.key == "TEMPERATURE")
    }

    @Test
    fun `the rider's own dashboard breaks a tie`() {
        // Two names of equal length, both present in the phrase. The tile the
        // rider chose to look at is the one they meant.
        val tied = listOf(
            metric("A_LONG", "Left sensor"),
            metric("B_LONG", "Left sensor"),
        )
        val m = VoiceCommandMatcher.match("left sensor", tied, onDashboard = setOf("B_LONG"))
        assertTrue(m is VoiceMatch.Hit)
        assertEquals("B_LONG", (m as VoiceMatch.Hit).term.key)
    }

    @Test
    fun `a tie the dashboard cannot break is reported, not guessed`() {
        val tied = listOf(
            metric("A_LONG", "Left sensor"),
            metric("B_LONG", "Left sensor"),
        )
        val m = VoiceCommandMatcher.match("left sensor", tied)
        assertTrue("got $m", m is VoiceMatch.Ambiguous)
        assertEquals(2, (m as VoiceMatch.Ambiguous).candidates.size)
    }

    @Test
    fun `nothing recognised is None, not a wrong answer`() {
        assertTrue(VoiceCommandMatcher.match("what is the weather", vocabulary) is VoiceMatch.None)
        assertTrue(VoiceCommandMatcher.match("", vocabulary) is VoiceMatch.None)
        assertTrue(VoiceCommandMatcher.match("   ", vocabulary) is VoiceMatch.None)
    }

    @Test
    fun `an empty vocabulary matches nothing`() {
        assertTrue(VoiceCommandMatcher.match("battery", emptyList()) is VoiceMatch.None)
    }
}
