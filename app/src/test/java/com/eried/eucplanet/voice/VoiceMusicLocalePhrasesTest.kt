package com.eried.eucplanet.voice

import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.MetricCatalog
import com.eried.eucplanet.voice.VoiceCommandMatcher.VoiceMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * The music phrases in every shipped language, matched against the whole
 * vocabulary the controller builds, so a translation cannot steal "stop
 * recording" or "last trip", or lose its own word to a neighbour.
 */
class VoiceMusicLocalePhrasesTest {

    private val resDir = File("src/main/res")
    private val music = setOf("MUSIC_START", "MEDIA_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS")

    /** R.string id to its resource name. */
    private val names: Map<Int, String> =
        R.string::class.java.fields.associate { it.getInt(null) to it.name }

    private fun localeDirs(): List<File> =
        resDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values") }
            ?.filter { File(it, "strings.xml").exists() }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun unescape(raw: String): String {
        var v = raw.trim()
        if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
        return v.replace("\\'", "'").replace("\\\"", "\"")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }

    private fun stringsIn(dir: File): Map<String, String> {
        val text = File(dir, "strings.xml").readText()
        val re = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return re.findAll(text).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
    }

    /** Same inputs as VoiceCommandController.vocabulary(), English as fallback like Android. */
    private fun vocabularyFor(dir: File): List<VoiceVocabulary.SpokenTerm> {
        val english = stringsIn(File(resDir, "values"))
        val local = stringsIn(dir)
        val get: (Int) -> String = { id ->
            val name = names.getValue(id)
            local[name] ?: english[name] ?: ""
        }
        return VoiceVocabulary.build(
            metricNames = MetricCatalog.all.associate { it.key to get(it.spokenLabelRes ?: it.labelRes) },
            reportNames = VoicePhrases.resolve(VoicePhrases.REPORTS, get),
            splitName = get(R.string.voice_split_term),
            helpPhrases = get(R.string.voice_help_terms),
            actionPhrases = VoicePhrases.resolve(VoicePhrases.ACTIONS, get),
            specialPhrases = VoicePhrases.resolve(VoicePhrases.SPECIALS, get),
        )
    }

    private fun describe(m: VoiceMatch): String = when (m) {
        is VoiceMatch.Hit -> m.term.key
        is VoiceMatch.Ambiguous -> "ambiguous ${m.candidates.map { it.key }.toSet()}"
        VoiceMatch.None -> "none"
    }

    @Test fun `the locale folders are really there`() {
        assertTrue(localeDirs().size >= 20)
    }

    @Test fun `every locale has phrases for each music action`() {
        val empty = mutableListOf<String>()
        for (dir in localeDirs()) {
            val strings = stringsIn(dir)
            for ((key, id) in VoicePhrases.ACTIONS) {
                if (key !in music) continue
                val phrases = strings[names.getValue(id)].orEmpty().split(",").filter { it.isNotBlank() }
                if (phrases.isEmpty()) empty += "${dir.name}/$key"
            }
        }
        assertEquals("no phrases: $empty", emptyList<String>(), empty)
    }

    @Test fun `each music phrase lands on its own action in every locale`() {
        val wrong = mutableListOf<String>()
        for (dir in localeDirs()) {
            val vocab = vocabularyFor(dir)
            for (term in vocab.filter { it.key in music }) {
                val m = VoiceCommandMatcher.match(term.name, vocab)
                if ((m as? VoiceMatch.Hit)?.term?.key != term.key) {
                    wrong += "${dir.name}: \"${term.name}\" is ${term.key}, matched ${describe(m)}"
                }
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun `no other phrase is taken by a music action in any locale`() {
        // RECORD_STOP and LAST_TRIP are the near neighbours ("stop", "last"),
        // but every other term is checked too.
        val stolen = mutableListOf<String>()
        for (dir in localeDirs()) {
            val vocab = vocabularyFor(dir)
            for (term in vocab.filter { it.key !in music }) {
                val m = VoiceCommandMatcher.match(term.name, vocab)
                val keys = when (m) {
                    is VoiceMatch.Hit -> setOf(m.term.key)
                    is VoiceMatch.Ambiguous -> m.candidates.map { it.key }.toSet()
                    VoiceMatch.None -> emptySet()
                }
                if (keys.any { it in music }) {
                    stolen += "${dir.name}: \"${term.name}\" is ${term.key}, matched ${describe(m)}"
                }
            }
        }
        assertEquals(emptyList<String>(), stolen)
    }

    private fun ownKeyFailures(key: String): List<String> {
        val wrong = mutableListOf<String>()
        for (dir in localeDirs()) {
            val vocab = vocabularyFor(dir)
            for (term in vocab.filter { it.key == key }) {
                val m = VoiceCommandMatcher.match(term.name, vocab)
                if ((m as? VoiceMatch.Hit)?.term?.key != term.key) {
                    wrong += "${dir.name}: \"${term.name}\" matched ${describe(m)}"
                }
            }
        }
        return wrong
    }

    @Test fun `stop recording reaches its own key in every locale`() {
        assertEquals(emptyList<String>(), ownKeyFailures("RECORD_STOP"))
    }

    // fi "viime matka" loses to the longer "Matkamittari" (matka is a prefix),
    // no "siste tur" ties with "Turteller". Not a music issue.
    @Test fun `last trip reaches its own key in every locale`() {
        assertEquals(emptyList<String>(), ownKeyFailures(VoiceVocabulary.Special.LAST_TRIP))
    }
}
