package com.eried.eucplanet.voice

/**
 * "max speed" is the speed metric with a statistic asked of it.
 *
 * The modifier is peeled off the phrase before the metric is matched, so
 * every metric gets max and average for free rather than the vocabulary
 * growing four entries per metric. Two hundred names for fifty things would
 * bury the list and give the matcher four ways to be ambiguous about each.
 *
 * A modifier and nothing else is not a question: "max" alone leaves an empty
 * phrase, and the caller treats that as not understood rather than guessing
 * which of fifty metrics was meant.
 *
 * Free of Android, so the rules are plain unit tests.
 */
object VoiceStatModifier {

    /** Which statistic was asked for, and the phrase with the word removed. */
    data class Parsed(val stat: Stat?, val rest: String)

    /**
     * The statistics worth saying out loud.
     *
     * A deliberate subset of what the dashboard can compute: a rider at speed
     * is not asking for the 95th percentile, and offering it would cost a
     * phrase list per language for something nobody says.
     */
    enum class Stat { MAX, MIN, AVG, PEAK }

    /**
     * @param phrases stat to its comma-separated phrasings, in the rider's
     *   language. Same shape as the help phrases and for the same reason:
     *   which words mean "highest" is a fact about a language.
     */
    fun parse(heard: String, phrases: Map<Stat, String>): Parsed {
        val words = heard.lowercase()
            .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
            .joinToString("")
            .split(" ")
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return Parsed(null, heard)

        for ((stat, list) in phrases) {
            for (raw in list.split(",")) {
                val term = raw.trim().lowercase()
                if (term.isBlank()) continue
                val termWords = term.split(" ").filter { it.isNotBlank() }
                val at = indexOfSequence(words, termWords)
                if (at >= 0) {
                    val rest = (words.subList(0, at) + words.subList(at + termWords.size, words.size))
                        .joinToString(" ")
                    return Parsed(stat, rest)
                }
            }
        }
        return Parsed(null, heard)
    }

    /** Where [needle] appears in [hay] as consecutive words, or -1. */
    private fun indexOfSequence(hay: List<String>, needle: List<String>): Int {
        if (needle.isEmpty() || needle.size > hay.size) return -1
        for (i in 0..(hay.size - needle.size)) {
            if ((needle.indices).all { hay[i + it] == needle[it] }) return i
        }
        return -1
    }
}
