package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceCommandMatcher.VoiceMatch
import com.eried.eucplanet.voice.VoiceVocabulary.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Voice off" while riding, which the widget has always had and the voice had
 * not.
 *
 * A tester asked for it from a helmet: the periodic announcements are welcome
 * on an empty road and unbearable in traffic, and the only way to stop them
 * was to pull over and find the widget. The same tester tried simply saying
 * "voice" and got nothing, which is the other half of this file: the words
 * have to be the ones a rider reaches for, and they have to stay apart from
 * each other.
 */
class VoiceAnnounceActionTest {

    // The English phrasings, as strings.xml carries them. Written out rather
    // than read from resources so this stays a plain unit test; the locale
    // coverage tests are what guard the other twenty-two languages.
    private val announceOn = "voice on,announcements on,turn on voice"
    private val announceOff = "voice off,announcements off,turn off voice,mute voice"
    private val report = "report,status,voice report"
    private val lightOn = "lights on,light on,turn on the lights"

    private val vocabulary = VoiceVocabulary.build(
        metricNames = mapOf("SPEED" to "Speed", "BATTERY" to "Battery"),
        reportNames = emptyMap(),
        splitName = "last split",
        helpPhrases = "help,what can I say",
        specialPhrases = mapOf(VoiceVocabulary.Special.REPORT to report),
        actionPhrases = mapOf(
            "V_ANNOUNCE_ON" to announceOn,
            "V_ANNOUNCE_OFF" to announceOff,
            "V_LIGHT_ON" to lightOn,
        ),
    )

    private fun hit(said: String): VoiceMatch =
        VoiceCommandMatcher.match(said, vocabulary, emptySet())

    @Test fun `turning the announcements on and off is one word each way`() {
        assertEquals("V_ANNOUNCE_ON", (hit("voice on") as VoiceMatch.Hit).term.key)
        assertEquals("V_ANNOUNCE_OFF", (hit("voice off") as VoiceMatch.Hit).term.key)
    }

    @Test fun `on and off do not reach each other`() {
        // The whole pair hangs on "on" and "off" telling themselves apart, and
        // they are shorter than the three-letter prefix the matcher needs. It
        // is the full-word equality that separates them, so a change to the
        // prefix rule must not quietly merge the two.
        val on = hit("voice on")
        val off = hit("voice off")
        assertTrue(on is VoiceMatch.Hit)
        assertTrue(off is VoiceMatch.Hit)
        assertTrue((on as VoiceMatch.Hit).term.key != (off as VoiceMatch.Hit).term.key)
    }

    @Test fun `the lights are not the announcements`() {
        // "lights on" and "voice on" share a word, and a matcher that only
        // needed one word in common would turn the headlight off when a rider
        // asked for quiet.
        assertEquals("V_LIGHT_ON", (hit("lights on") as VoiceMatch.Hit).term.key)
        assertEquals("V_ANNOUNCE_ON", (hit("turn on voice") as VoiceMatch.Hit).term.key)
    }

    @Test fun `asking for the report is not asking to switch it off`() {
        // "voice report" and "voice off" both start with the word the tester
        // reached for. They have to land on different things, and neither may
        // come back ambiguous: a rider at speed gets one answer, not a
        // question back.
        val said = hit("voice report")
        assertTrue("voice report was $said", said is VoiceMatch.Hit)
        assertEquals(VoiceVocabulary.Special.REPORT, (said as VoiceMatch.Hit).term.key)
    }

    @Test fun `the bare word is refused rather than guessed`() {
        // "voice" alone could mean the report, on, or off. Guessing which
        // would switch a rider's announcements off when they asked to hear
        // one, so it is not understood, and the list shows all three phrases.
        assertTrue(hit("voice") is VoiceMatch.None)
    }

    @Test fun `both are actions, so neither waits on a wheel`() {
        // Switching the announcements is a settings write. A rider between
        // rides, with nothing connected, is exactly who wants it quiet.
        val keys = vocabulary.filter { it.key.startsWith("V_ANNOUNCE") }
        assertTrue(keys.isNotEmpty())
        assertTrue(keys.all { it.kind == Kind.ACTION })
    }

    @Test fun `the name spoken back is the phrase a rider said`() {
        // Actions acknowledge themselves by name, and the name is the first
        // phrasing. "voice off" is what they said and what they hear back.
        val off = vocabulary.first { it.key == "V_ANNOUNCE_OFF" }
        assertEquals("voice off", off.name)
    }
}
