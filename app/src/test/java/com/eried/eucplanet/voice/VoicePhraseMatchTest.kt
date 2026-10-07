package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceCommandMatcher.VoiceMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How riders actually phrase a question.
 *
 * Nobody says "controller temperature" out loud. They say "controller temp",
 * and the first version of this matcher answered that with "I did not catch
 * that", because it looked for the whole label as one run of characters. These
 * are the phrases that found it.
 *
 * The last test is the one that matters for maintenance: it reads the real
 * catalog rather than this fixture, so a metric added next year cannot quietly
 * ship a label no rider can say.
 */
class VoicePhraseMatchTest {

    private val vocabulary = VoiceVocabulary.build(
        metricNames = mapOf(
            "MOTOR_TEMP" to "Motor temperature",
            "CONTROLLER_TEMP" to "Controller temperature",
            "BATTERY_TEMP" to "Battery temperature",
            "BATTERY" to "Battery",
            "BATTERY_ENVELOPE" to "Battery (est)",
            "WH_PER_KM" to "Consumption",
            "RANGE_ESTIMATE" to "Range",
            "TIRE_PRESSURE" to "Tire pressure",
            "G_FORCE" to "G-force",
        ),
        reportNames = mapOf("Temp" to "Temp"),
        splitName = "Last split",
    )

    private fun hit(phrase: String, onDashboard: Set<String> = emptySet()): String? =
        when (val m = VoiceCommandMatcher.match(phrase, vocabulary, onDashboard)) {
            is VoiceMatch.Hit -> m.term.key
            else -> null
        }

    @Test
    fun `a rider can shorten a word`() {
        // The case that started this. "temp" is how the word gets said.
        assertEquals("CONTROLLER_TEMP", hit("controller temp"))
        assertEquals("MOTOR_TEMP", hit("motor temp"))
        assertEquals("CONTROLLER_TEMP", hit("what's my controller temp"))
    }

    @Test
    fun `shortening does not collapse the three temperatures`() {
        // The risk in being lenient: "motor temp" must not start matching the
        // controller, and a bare "temp" must not silently pick one of them.
        assertEquals("MOTOR_TEMP", hit("motor temp"))
        assertEquals("BATTERY_TEMP", hit("battery temp"))
        // Bare "temp" names the report, which is the one that means "the
        // temperature" without qualification.
        assertEquals("Temp", hit("temp"))
    }

    @Test
    fun `word order does not decide which one is meant`() {
        // The label reads "Battery (est)", the rider says it the other way
        // round. Both are the estimate; neither is plain battery.
        assertEquals("BATTERY_ENVELOPE", hit("battery estimation"))
        assertEquals("BATTERY_ENVELOPE", hit("estimated battery"))
        // And asking for battery alone still means battery: the estimate needs
        // its second word to be said.
        assertEquals("BATTERY", hit("battery"))
        assertEquals("BATTERY", hit("how's the battery"))
    }

    @Test
    fun `a word shorter than the floor has to be said exactly`() {
        // "g" cannot prefix-match its way into anything, so a phrase has to
        // actually contain it. This is what stops one-letter words in labels
        // from matching most of the language.
        assertEquals("G_FORCE", hit("what's the g force"))
        assertEquals(null, hit("how much force"))
    }

    @Test
    fun `a phrase with none of the words is still not understood`() {
        // Leniency has to stop somewhere, or every question gets an answer to
        // some other question.
        assertEquals(null, hit("how much am i using"))
        assertEquals(null, hit("what's the weather"))
        assertEquals(null, hit(""))
    }

    @Test
    fun `every catalog label can be said back`() {
        // The drift guard. Reads the real labels, so adding a metric with a
        // label nobody can say - blank, punctuation only, or one that is eaten
        // whole by another label - fails here rather than in a rider's ear.
        val labels = catalogLabels()
        assertTrue("found no metric labels to check", labels.size > 30)

        val vocab = VoiceVocabulary.build(
            metricNames = labels,
            reportNames = emptyMap(),
            splitName = "Last split",
        )
        for ((key, label) in labels) {
            val m = VoiceCommandMatcher.match(label, vocab, setOf(key))
            assertTrue(
                "\"$label\" ($key) cannot be matched by saying it: $m",
                m is VoiceMatch.Hit && m.term.key == key,
            )
        }
    }

    /** The English label of every metric in the catalog, read from source. */
    private fun catalogLabels(): Map<String, String> {
        val catalog = File("src/main/java/com/eried/eucplanet/data/model/MetricCatalog.kt")
            .readText()
        val strings = stringValues()
        // key = "SPEED", ... labelRes = R.string.metric_chip_speed
        val entries = Regex(
            """key\s*=\s*"([A-Z0-9_]+)"[\s\S]{0,400}?labelRes\s*=\s*R\.string\.(\w+)"""
        ).findAll(catalog)
        return entries.mapNotNull { m ->
            val label = strings[m.groupValues[2]] ?: return@mapNotNull null
            m.groupValues[1] to label
        }.toMap()
    }

    private fun stringValues(): Map<String, String> {
        val xml = File("src/main/res/values/strings.xml").readText()
        return Regex("""<string name="(\w+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\'", "'") }
    }
}
