package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceVocabulary.SpokenTerm

/**
 * What the rider meant, out of what the recogniser heard.
 *
 * A rider does not say a key, they say a sentence: "what is my battery", "how
 * hot is the motor", "consumption". So this looks for any known name inside the
 * phrase rather than demanding the phrase be a name.
 *
 * And they shorten things. Nobody says "controller temperature" out loud, they
 * say "controller temp", so a name is matched word by word with the last part
 * of a word allowed to be missing, rather than as one exact run of characters.
 * That also stops word order from mattering: "estimated battery" finds the
 * estimate rather than landing on plain battery because the label happened to
 * read the other way round. It is a rule and not a list of phrasings, which is
 * the point: a metric added tomorrow gets all of this from the label alone,
 * with nothing to keep in sync and nothing new to translate.
 *
 * The hard part is not matching, it is the near neighbours. There are three
 * temperatures in the catalog (motor, controller, battery) and two speed
 * limits, so "temperature" on its own is genuinely ambiguous and "motor
 * temperature" must not be answered with the plain temperature. Two rules
 * handle both: the longest name wins, and where several still tie, the rider's
 * own dashboard decides, because a tile they chose to look at is the one they
 * meant. Neither rule needs a setting.
 *
 * Free of Android, so every rule below is a plain unit test.
 */
object VoiceCommandMatcher {

    sealed interface VoiceMatch {
        /** Exactly one thing was meant. */
        data class Hit(val term: SpokenTerm) : VoiceMatch

        /** Several were equally plausible and the dashboard did not decide. */
        data class Ambiguous(val candidates: List<SpokenTerm>) : VoiceMatch

        /** Nothing in the vocabulary appeared in the phrase. */
        data object None : VoiceMatch
    }

    /**
     * @param heard        what the recogniser returned
     * @param vocabulary   from [VoiceVocabulary.build]
     * @param onDashboard  metric keys the rider currently has as tiles, used
     *                     only to break a tie
     */
    fun match(
        heard: String,
        vocabulary: List<SpokenTerm>,
        onDashboard: Set<String> = emptySet(),
    ): VoiceMatch {
        val phrase = normalise(heard)
        if (phrase.isBlank()) return VoiceMatch.None

        val spoken = phrase.split(" ").filter { it.isNotBlank() }
        val present = vocabulary.filter { term ->
            val name = normalise(term.name)
            name.isNotBlank() && covers(spoken, name.split(" "))
        }
        if (present.isEmpty()) return VoiceMatch.None

        // Longest first: "motor temperature" beats "temperature", which is the
        // whole reason a rider can ask for a specific one at all.
        val longest = present.maxOf { normalise(it.name).length }
        val best = present.filter { normalise(it.name).length == longest }
        if (best.size == 1) return VoiceMatch.Hit(best.first())

        // Still tied. The rider's own tiles decide.
        val onTiles = best.filter { it.key in onDashboard }
        if (onTiles.size == 1) return VoiceMatch.Hit(onTiles.first())

        return VoiceMatch.Ambiguous(best)
    }

    /**
     * True when every word of a name is somewhere in what the rider said.
     *
     * Order is not required, because a rider reaching for a name rarely
     * reproduces the label's word order, and requiring it buys nothing: the
     * words themselves are specific enough. "Motor temperature" still cannot
     * match a phrase that never says motor, which is what keeps the three
     * temperatures apart.
     */
    private fun covers(spoken: List<String>, nameWords: List<String>): Boolean =
        nameWords.all { word -> spoken.any { matches(it, word) } }

    /**
     * One spoken word against one word of a name.
     *
     * Either may be the shortened one: a rider says "temp" for temperature,
     * and a recogniser hearing "temperatures" should still land on a label
     * that reads "temperature". Three characters is the floor, so that a stray
     * short word cannot drag in a name: it is enough for "est" in
     * "Battery (est)" to find "estimated", and short enough to stay honest.
     * Anything shorter has to be said exactly, which is what "PWM" and the "g"
     * of "g force" want anyway.
     */
    private fun matches(spokenWord: String, nameWord: String): Boolean {
        if (spokenWord == nameWord) return true
        val shorter = if (spokenWord.length <= nameWord.length) spokenWord else nameWord
        val longer = if (shorter === spokenWord) nameWord else spokenWord
        return shorter.length >= MIN_PREFIX && longer.startsWith(shorter)
    }

    /** Below this, a word has to be said exactly. */
    private const val MIN_PREFIX = 3

    /**
     * Lower case, punctuation out, runs of blanks collapsed. Recognisers differ
     * on commas and question marks, and none of that changes what was asked.
     */
    private fun normalise(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
            .joinToString("")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ")
}
