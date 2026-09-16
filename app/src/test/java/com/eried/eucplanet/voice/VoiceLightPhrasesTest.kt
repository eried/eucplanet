package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceCommandMatcher.VoiceMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Five ways to say "light", landing on four different things.
 *
 * The bare word is the command, because that is what a rider says when they
 * want the light switched and it is what they said before this existed. The
 * reading had to move out of its way and ask for itself by a longer name,
 * which works because the matcher prefers the longest name that fits: "light
 * status" beats both the toggle and the "status" report, and neither of those
 * has to know the other exists.
 *
 * Every phrase here overlaps with at least one other on purpose. That is the
 * point of the file: the separation is a property of the lengths, not of any
 * rule written down in one place, so it needs holding still.
 */
class VoiceLightPhrasesTest {

    private val vocabulary = VoiceVocabulary.build(
        metricNames = mapOf(
            "LIGHT_ON" to "light status",
            "SPEED" to "Speed",
        ),
        reportNames = emptyMap(),
        splitName = "last split",
        helpPhrases = "help,what can I say",
        // The report special answers to "status" on its own, which is the
        // phrase "light status" has to get past.
        specialPhrases = mapOf(VoiceVocabulary.Special.REPORT to "report,status,voice report"),
        actionPhrases = mapOf(
            "LIGHT_TOGGLE" to "light,lights,toggle the light",
            "V_LIGHT_ON" to "lights on,light on,turn on the lights",
            "V_LIGHT_OFF" to "lights off,light off,turn off the lights",
        ),
    )

    private fun key(said: String): String? =
        (VoiceCommandMatcher.match(said, vocabulary, emptySet()) as? VoiceMatch.Hit)?.term?.key

    @Test fun `the bare word switches the light`() {
        assertEquals("LIGHT_TOGGLE", key("light"))
        assertEquals("LIGHT_TOGGLE", key("lights"))
    }

    @Test fun `asking for the status reads it instead`() {
        // The whole reason the reading was renamed. "light" alone must not
        // land here, or a rider who wanted the light on gets told it is off.
        assertEquals("LIGHT_ON", key("light status"))
    }

    @Test fun `the status phrase does not fall into the report`() {
        // "status" on its own is the spoken report, and it is a whole word
        // inside "light status". Longest-name-wins is what separates them.
        assertEquals(VoiceVocabulary.Special.REPORT, key("status"))
        assertEquals("LIGHT_ON", key("light status"))
    }

    @Test fun `saying which way still wins over the toggle`() {
        // A rider who knows what they want should get it, not a flip. These
        // all contain the toggle's own word.
        assertEquals("V_LIGHT_ON", key("lights on"))
        assertEquals("V_LIGHT_ON", key("light on"))
        assertEquals("V_LIGHT_ON", key("turn on the lights"))
        assertEquals("V_LIGHT_OFF", key("lights off"))
        assertEquals("V_LIGHT_OFF", key("light off"))
        assertEquals("V_LIGHT_OFF", key("turn off the lights"))
    }

    @Test fun `nothing about the light is ever ambiguous`() {
        // An ambiguous match asks the rider a question back, which at speed
        // is worse than either answer. Every phrasing has to resolve.
        val said = listOf(
            "light", "lights", "light status", "status",
            "lights on", "light on", "turn on the lights",
            "lights off", "light off", "turn off the lights",
            "toggle the light",
        )
        for (phrase in said) {
            val m = VoiceCommandMatcher.match(phrase, vocabulary, emptySet())
            assertTrue("\"$phrase\" came back as $m", m is VoiceMatch.Hit)
        }
    }

    @Test fun `the toggle goes to the wheel's own light command`() {
        // Not a V_ key: the catalog already has one lights command, and the
        // on and off pair exist only because "lights on" is a statement about
        // the result rather than a request to flip. A plain toggle is the
        // catalog action itself and needs no translation in runAction.
        val toggle = vocabulary.first { it.key == "LIGHT_TOGGLE" }
        assertEquals(VoiceVocabulary.Kind.ACTION, toggle.kind)
        assertEquals("light", toggle.name)
    }
}
