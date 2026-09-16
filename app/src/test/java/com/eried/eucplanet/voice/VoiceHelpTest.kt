package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceAnswer.Answer
import com.eried.eucplanet.voice.VoiceCommandSession.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asking what can be asked.
 *
 * A rider who has forgotten the list is wearing gloves and looking at the road,
 * so the way back to it has to be spoken, not a dialog. "Help" and "what can I
 * say" are the same request, which is why they share a key.
 */
class VoiceHelpTest {

    private val vocabulary = VoiceVocabulary.build(
        metricNames = mapOf(
            "BATTERY" to "Battery",
            "SPEED" to "Speed",
            "WH_PER_KM" to "Consumption",
            "MOTOR_TEMP" to "Motor temperature",
        ),
        reportNames = emptyMap(),
        splitName = "Last split",
        helpPhrases = "help,what can I say,what can I do",
    )

    private fun ask(phrase: String, onDashboard: Set<String> = emptySet()) =
        VoiceCommandSession.answer(phrase, vocabulary, onDashboard) {
            Reading("should not be read", null)
        }

    @Test
    fun `every phrasing reaches the same answer`() {
        for (phrase in listOf("help", "what can I say", "what can I do", "ok help me")) {
            assertTrue("\"$phrase\" did not offer examples", ask(phrase) is Answer.Examples)
        }
    }

    @Test
    fun `it offers three real names, not the whole catalog`() {
        val answer = ask("what can I say") as Answer.Examples
        assertEquals(3, answer.names.size)
        // Real terms, so a rider can repeat one straight back.
        val known = vocabulary.map { it.name }
        assertTrue(answer.names.all { it in known })
    }

    @Test
    fun `it never offers help as an example of what to say`() {
        // Suggesting "help" to somebody who just said "help" is a loop.
        val answer = ask("help") as Answer.Examples
        assertFalse(answer.names.any { it == "help" || it == "what can I say" })
    }

    @Test
    fun `the rider's own tiles come first`() {
        // Same instinct as the not-understood examples: the tiles they chose
        // are the values they care about.
        val answer = ask("help", onDashboard = setOf("WH_PER_KM")) as Answer.Examples
        assertEquals(listOf("Consumption"), answer.names)
    }

    @Test
    fun `a longer phrasing beats the bare word inside it`() {
        // "what can I do" contains no other term, but the rule that picks the
        // longest name is what stops "help" matching first and is worth
        // pinning: both are the same key here, so the test is that it resolves
        // at all rather than going ambiguous.
        assertTrue(ask("so what can I do") is Answer.Examples)
    }

    @Test
    fun `asking for a metric still answers the metric`() {
        // Adding help phrases must not shadow the vocabulary itself.
        val answer = VoiceCommandSession.answer("battery", vocabulary, emptySet()) {
            Reading("51%", null)
        }
        assertTrue(answer is Answer.Say)
        assertEquals("51%", (answer as Answer.Say).value)
    }
}
