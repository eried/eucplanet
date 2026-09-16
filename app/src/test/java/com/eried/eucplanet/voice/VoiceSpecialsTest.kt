package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceAnswer.Answer
import com.eried.eucplanet.voice.VoiceCommandSession.Reading
import com.eried.eucplanet.voice.VoiceVocabulary.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The things a rider asks that are not wheel readings, and the few they ask
 * the app to do.
 *
 * Both are many-phrasings-one-key like help, because "weather" and "is it a
 * good day to ride" are one question, and because which phrasings exist is a
 * fact about a language rather than about the app.
 */
class VoiceSpecialsTest {

    private val vocabulary = VoiceVocabulary.build(
        metricNames = mapOf("BATTERY" to "Battery", "SPEED" to "Speed"),
        reportNames = emptyMap(),
        splitName = "Last split",
        helpPhrases = "help",
        specialPhrases = mapOf(
            VoiceVocabulary.Special.WEATHER to "weather,good day to ride",
            VoiceVocabulary.Special.CONNECTED to "connected,is the wheel connected",
            VoiceVocabulary.Special.NAV_NEXT to "navigation,what is next,repeat",
        ),
        actionPhrases = mapOf(
            "V_LIGHT_ON" to "lights on,light on",
            "HORN" to "horn,beep",
            "RECORD_STOP" to "stop recording",
        ),
    )

    private fun ask(phrase: String) = VoiceCommandSession.answer(
        heard = phrase,
        vocabulary = vocabulary,
        onDashboard = emptySet(),
        needsConfirm = { it == "RECORD_STOP" },
    ) { term ->
        // Specials answer with a whole sentence, the way a report does.
        if (term.kind == Kind.SPECIAL) Reading(null, null, reportText = "sentence for ${term.key}")
        else Reading("51%", null)
    }

    @Test
    fun `every phrasing of a special reaches its key`() {
        for (phrase in listOf("weather", "is it a good day to ride", "what's the weather")) {
            val a = ask(phrase)
            assertTrue("\"$phrase\" gave $a", a is Answer.SayReport)
            assertEquals("sentence for ${VoiceVocabulary.Special.WEATHER}", (a as Answer.SayReport).text)
        }
    }

    @Test
    fun `a special is spoken whole, not prefixed with its name`() {
        // Same reason a report is: the sentence already reads as an answer,
        // and "weather. Good riding weather" says it twice.
        val a = ask("weather") as Answer.SayReport
        assertFalse(a.text.startsWith(a.name))
    }

    @Test
    fun `an action is something to do, not something to say`() {
        val a = ask("lights on")
        assertTrue("gave $a", a is Answer.Act)
        assertEquals("V_LIGHT_ON", (a as Answer.Act).key)
        assertFalse("lights should not need confirming", a.confirm)
    }

    @Test
    fun `the destructive one asks first`() {
        // Stopping a recording ends a ride that cannot be resumed, so a
        // misheard word must not be able to do it.
        val a = ask("stop recording") as Answer.Act
        assertEquals("RECORD_STOP", a.key)
        assertTrue(a.confirm)
    }

    @Test
    fun `the horn does not ask first`() {
        // Confirming a horn would take longer than the reason for sounding it.
        assertFalse((ask("beep") as Answer.Act).confirm)
    }

    @Test
    fun `an action never reads a value`() {
        // The read lambda returns a battery percentage for anything that is
        // not a special. An action reaching it would answer "51%" to "horn".
        assertTrue(ask("horn") is Answer.Act)
    }

    @Test
    fun `specials and actions do not shadow the metrics`() {
        val a = ask("battery")
        assertTrue("gave $a", a is Answer.Say)
        assertEquals("51%", (a as Answer.Say).value)
    }

    @Test
    fun `help still wins over everything`() {
        assertTrue(ask("help") is Answer.Examples)
    }
}
