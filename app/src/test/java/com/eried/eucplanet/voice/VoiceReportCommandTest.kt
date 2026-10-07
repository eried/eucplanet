package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceAnswer.Answer
import com.eried.eucplanet.voice.VoiceCommandSession.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Report" has to actually report.
 *
 * It answered "No report yet" for as long as it existed, under a comment
 * saying it was handled somewhere earlier. It was not handled anywhere: the
 * special returned null, null means no data, and no data is a sentence about
 * there being nothing to say. A rider asking the app to talk got told it had
 * nothing to talk about while the dashboard button beside them worked.
 *
 * The shape of the bug is what this pins: a special that yields no text is
 * indistinguishable from one that failed, so the one that must never be
 * silent needs a test that it is not.
 */
class VoiceReportCommandTest {

    private val vocabulary = VoiceVocabulary.build(
        metricNames = mapOf("SPEED" to "Speed", "BATTERY" to "Battery"),
        reportNames = emptyMap(),
        splitName = "last split",
        helpPhrases = "help,what can I say",
        specialPhrases = mapOf(VoiceVocabulary.Special.REPORT to "report,status,voice report"),
        actionPhrases = emptyMap(),
    )

    private fun answer(said: String, text: String?): Answer =
        VoiceCommandSession.answer(
            heard = said,
            vocabulary = vocabulary,
            onDashboard = emptySet(),
        ) { term ->
            if (term.key == VoiceVocabulary.Special.REPORT) {
                if (text == null) Reading(null, VoiceAnswer.Reason.NO_DATA_YET)
                else Reading(null, null, reportText = text)
            } else {
                Reading("0", null)
            }
        }

    @Test fun `all three phrasings reach the report`() {
        for (said in listOf("report", "status", "voice report")) {
            val a = answer(said, "Speed 20, battery 60 percent")
            assertTrue("\"$said\" gave $a", a is Answer.SayReport)
        }
    }

    @Test fun `the sentence the report built is the sentence spoken`() {
        // Not a name and a value: the report already phrases itself, in the
        // rider's units and language, and prefixing it would say
        // "report, speed 20, battery 60 percent".
        val a = answer("report", "Speed 20, battery 60 percent") as Answer.SayReport
        assertEquals("Speed 20, battery 60 percent", a.text)
    }

    @Test fun `an empty report still says something`() {
        // Every item switched off, or a wheel that has sent nothing. The
        // rider hears why rather than silence, which is the one response that
        // cannot be told apart from the feature being broken.
        val a = answer("report", null)
        assertTrue("expected an explanation, got $a", a is Answer.Unavailable)
        assertEquals(VoiceAnswer.Reason.NO_DATA_YET, (a as Answer.Unavailable).reason)
    }

    @Test fun `the report is answerable with no wheel connected`() {
        // Phone battery, the clock and the recording state are all worth
        // hearing between rides, so this one is not gated on a connection.
        assertTrue(VoiceVocabulary.Special.REPORT in OFF_WHEEL)
    }
}
