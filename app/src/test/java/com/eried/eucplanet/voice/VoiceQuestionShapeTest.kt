package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceCommandMatcher.VoiceMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Riders ask questions, they do not read out catalog entries.
 *
 * "What is my battery" has to work as well as "battery", and it does, because
 * the matcher looks for a name inside the phrase rather than asking the phrase
 * to be a name. That is easy to break by tightening the matcher later, and
 * nothing else would notice, so it is pinned here.
 *
 * The limit is worth pinning too: a question that never says the name cannot
 * be answered. "How fast am I going" has no word in common with Speed.
 */
class VoiceQuestionShapeTest {

    private val vocabulary = VoiceVocabulary.build(
        metricNames = mapOf(
            "SPEED" to "Speed",
            "BATTERY" to "Battery",
            "WH_PER_KM" to "Consumption",
            "CONTROLLER_TEMP" to "Controller temperature",
            "RANGE_ESTIMATE" to "Range",
            "LOAD" to "PWM",
        ),
        reportNames = mapOf("Temp" to "Temp"),
        splitName = "Last split",
    )

    private fun key(phrase: String): String? =
        when (val m = VoiceCommandMatcher.match(phrase, vocabulary, setOf("BATTERY", "SPEED"))) {
            is VoiceMatch.Hit -> m.term.key
            else -> null
        }

    @Test
    fun `a question around the name is answered`() {
        assertEquals("BATTERY", key("what's my battery"))
        assertEquals("BATTERY", key("what is my battery level"))
        assertEquals("BATTERY", key("how much battery do I have"))
        assertEquals("BATTERY", key("is my battery ok"))
        assertEquals("SPEED", key("whats my speed"))
        assertEquals("LOAD", key("hey what is the pwm"))
        assertEquals("RANGE_ESTIMATE", key("tell me the range"))
        assertEquals("WH_PER_KM", key("how's my consumption"))
    }

    @Test
    fun `the bare name still works`() {
        // Both shapes, because riders at speed shorten to one word.
        assertEquals("BATTERY", key("battery"))
        assertEquals("BATTERY", key("battery?"))
    }

    @Test
    fun `a question and a shortened word together`() {
        // The two leniencies have to compose: this is the phrase a rider
        // actually says, and it needs both the wrapper words ignored and
        // "temp" accepted for "temperature".
        assertEquals("CONTROLLER_TEMP", key("what's my controller temp"))
    }

    @Test
    fun `splits can be asked for the same way`() {
        assertEquals(VoiceVocabulary.SPLIT_KEY, key("what about the last split"))
    }

    @Test
    fun `a question that never says the name cannot be answered`() {
        // The honest limit, and the reason the dialog lists names. Answering
        // this would need synonyms, which is a list somebody has to maintain
        // in 23 languages.
        assertNull(key("how fast am I going"))
        assertNull(key("what's the weather like"))
    }
}
