package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceAnswer.Answer
import com.eried.eucplanet.voice.VoiceCommandMatcher.VoiceMatch
import com.eried.eucplanet.voice.VoiceVocabulary.SpokenTerm

/**
 * One question, from the phrase to the sentence.
 *
 * The whole chain, with nothing Android in it: what was heard goes to the
 * matcher, what the matcher decided goes to the answer, and what comes out is
 * what to say. The caller supplies the two things that do need Android, the
 * vocabulary in the rider's language and a way to read a value, and gets back a
 * sentence to speak.
 *
 * Keeping it here rather than in a service is what lets a whole session be a
 * unit test: phrase in, spoken answer out, including every way it can fail.
 */
object VoiceCommandSession {

    /**
     * What the app knows about one thing a rider might ask for, at the moment
     * they ask. Null [value] with null [unavailable] means nothing has arrived
     * yet, which is itself an answer.
     */
    data class Reading(
        val value: String?,
        val unavailable: VoiceAnswer.Reason?,
        /** Set when a spoken report has already phrased this one. */
        val reportText: String? = null,
    )

    /**
     * @param heard       what the recogniser returned
     * @param vocabulary  from [VoiceVocabulary.build], in the rider's language
     * @param onDashboard metric keys the rider has as tiles, to break a tie
     * @param read        the current reading for a term, or null if unknown
     * @param needsConfirm whether an action key is one to ask about first
     */
    fun answer(
        heard: String,
        vocabulary: List<SpokenTerm>,
        onDashboard: Set<String>,
        // Before `read` on purpose: `read` stays the trailing lambda, so every
        // existing call site keeps reading as answer(...) { term -> ... }.
        needsConfirm: (String) -> Boolean = { false },
        read: (SpokenTerm) -> Reading?,
    ): Answer = when (val m = VoiceCommandMatcher.match(heard, vocabulary, onDashboard)) {
        is VoiceMatch.Hit -> if (m.term.kind == VoiceVocabulary.Kind.HELP) {
            // Nothing to read: they asked what to ask for.
            VoiceAnswer.examples(vocabulary, onDashboard)
        } else if (m.term.kind == VoiceVocabulary.Kind.ACTION) {
            // Nothing to read either: this one is a thing to do. Whether it
            // needs confirming is the caller's to decide, since it depends on
            // what the action costs rather than on the words.
            VoiceAnswer.Answer.Act(m.term.key, m.term.name, confirm = needsConfirm(m.term.key))
        } else {
            val reading = read(m.term)
            VoiceAnswer.answerFor(
                m.term, reading?.value, reading?.unavailable, reading?.reportText,
            )
        }
        // Two names, not five: the rider is moving, and a spoken list is no
        // help at speed.
        is VoiceMatch.Ambiguous -> Answer.NeedsChoice(m.candidates.take(2).map { it.name })
        VoiceMatch.None -> VoiceAnswer.notUnderstood(vocabulary, onDashboard)
    }
}
