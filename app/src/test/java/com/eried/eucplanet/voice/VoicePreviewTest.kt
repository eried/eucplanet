package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceAnswer.Answer
import com.eried.eucplanet.voice.VoiceAnswer.Reason
import com.eried.eucplanet.voice.VoiceCommandSession.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tapping a name in What can I say answers it.
 *
 * The preview exists because the acoustics are the one part of this feature
 * that cannot be tested without a person in the room: everything from the
 * heard phrase onwards can, and this is the entry point that lets it be. So
 * the property worth pinning is that a name taken straight off that list is
 * always answerable. A name the list offers and the matcher then refuses would
 * be the feature lying to a rider in the one place it explains itself.
 */
class VoicePreviewTest {

    private val metrics = mapOf(
        "SPEED" to "Speed",
        "BATTERY" to "Battery",
        "PWM" to "PWM",
        "VOLTAGE" to "Voltage",
        "MOTOR_TEMP" to "Motor temperature",
    )
    private val reports = mapOf("Speed" to "Speed", "Battery" to "Battery")
    private val vocabulary = VoiceVocabulary.build(metrics, reports, "last split")

    @Test
    fun `every name the list offers is answerable`() {
        val names = vocabulary.map { it.name }.distinct()
        assertTrue("the list is empty, so it promises nothing", names.isNotEmpty())
        for (name in names) {
            val answer = VoiceCommandSession.answer(
                heard = name,
                vocabulary = vocabulary,
                onDashboard = metrics.keys,
                read = { Reading("ok", null) },
            )
            assertTrue(
                "the list offers \"$name\" but asking for it gives $answer",
                answer is Answer.Say,
            )
        }
    }

    @Test
    fun `a name that is both a metric and a report still resolves`() {
        // Speed is in both catalogs. The list shows it once; a tap must not
        // land on the ambiguity branch meant for two different things sharing
        // a word.
        val answer = VoiceCommandSession.answer(
            heard = "Speed",
            vocabulary = vocabulary,
            onDashboard = metrics.keys,
            read = { Reading("30 km/h", null) },
        )
        assertTrue(answer is Answer.Say)
    }

    @Test
    fun `a preview reports what the wheel cannot do, rather than inventing it`() {
        // The same refusal a spoken question gets. A rider previewing a metric
        // their wheel does not send should hear that, not a plausible number.
        val answer = VoiceCommandSession.answer(
            heard = "PWM",
            vocabulary = vocabulary,
            onDashboard = metrics.keys,
            read = { Reading(null, Reason.UNSUPPORTED_BY_WHEEL) },
        )
        assertTrue(answer is Answer.Unavailable)
        assertEquals(
            Reason.UNSUPPORTED_BY_WHEEL,
            (answer as Answer.Unavailable).reason,
        )
    }

    @Test
    fun `a term with a spoken report answers with the report's own sentence`() {
        // This is the bug the on-device preview found. Battery, Speed, Amps,
        // Power, PWM, Temp and Trip all have a spoken report, and the reading
        // for them used to come back null so the report could phrase them
        // later. Null meant "nothing known", so every one of those answered
        // "No Battery yet" while the tile beside it read 45%, and the report
        // branch that was supposed to rescue them only ran for an answer that
        // could never be produced. The sentence is now fetched up front.
        val answer = VoiceCommandSession.answer(
            heard = "battery",
            vocabulary = vocabulary,
            onDashboard = metrics.keys,
            read = { Reading(null, null, reportText = "battery 45 percent") },
        )
        assertTrue("answered $answer instead of speaking the report", answer is Answer.SayReport)
        assertEquals("battery 45 percent", (answer as Answer.SayReport).text)
    }

    @Test
    fun `the report sentence is spoken as it stands, not prefixed with the name`() {
        // A report says its own name. Treating it as a value would give
        // "Battery, battery 45 percent", which is why it is a separate answer.
        val answer = VoiceCommandSession.answer(
            heard = "battery",
            vocabulary = vocabulary,
            onDashboard = metrics.keys,
            read = { Reading(null, null, reportText = "battery 45 percent") },
        ) as Answer.SayReport
        assertEquals("Battery", answer.name)
        assertTrue(
            "the name would be said twice",
            !answer.text.startsWith("${answer.name},"),
        )
    }

    @Test
    fun `a report with nothing to say is no data yet, not silence`() {
        // A wheel that has sent nothing has no report parts. The rider still
        // gets told, rather than the app going quiet on them.
        val answer = VoiceCommandSession.answer(
            heard = "battery",
            vocabulary = vocabulary,
            onDashboard = metrics.keys,
            read = { Reading(null, VoiceAnswer.Reason.NO_DATA_YET) },
        )
        assertTrue(answer is Answer.Unavailable)
    }

    @Test
    fun `a reason still beats a report sentence`() {
        // Legal mode silences reports, and a stale sentence must not outrank
        // the wheel saying it cannot report this at all.
        val answer = VoiceCommandSession.answer(
            heard = "battery",
            vocabulary = vocabulary,
            onDashboard = metrics.keys,
            read = { Reading(null, Reason.UNSUPPORTED_BY_WHEEL, reportText = "battery 45 percent") },
        )
        assertTrue(answer is Answer.Unavailable)
    }

    @Test
    fun `the list offers the specials and actions too, one name each`() {
        // The dialog used to build only metrics, reports and the split, so the
        // page that explains the feature omitted every special and action.
        // Several phrasings share a key; the list shows the first, and the
        // matcher still accepts the rest whether or not they are written down.
        val full = VoiceVocabulary.build(
            metricNames = metrics,
            reportNames = reports,
            splitName = "last split",
            helpPhrases = "help,what can I say",
            specialPhrases = mapOf("SP_WEATHER" to "weather,good day to ride"),
            actionPhrases = mapOf("HORN" to "horn,beep"),
        )
        assertEquals(2, full.count { it.key == "SP_WEATHER" })
        assertEquals(2, full.count { it.key == "HORN" })

        // What the dialog renders: one row per key for these kinds.
        val shown = full
            .groupBy {
                if (it.kind == VoiceVocabulary.Kind.METRIC ||
                    it.kind == VoiceVocabulary.Kind.REPORT ||
                    it.kind == VoiceVocabulary.Kind.SPLIT
                ) it.name else it.key
            }
            .map { (_, g) -> g.first().name }
        assertTrue("weather missing from the list", "weather" in shown)
        assertTrue("horn missing from the list", "horn" in shown)
        assertTrue("help missing from the list", "help" in shown)
        assertEquals(1, shown.count { it == "weather" })
        assertTrue("a second phrasing leaked in", "good day to ride" !in shown)
    }
}
