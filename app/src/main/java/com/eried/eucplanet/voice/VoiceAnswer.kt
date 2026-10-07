package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceVocabulary.SpokenTerm

/**
 * What to say back.
 *
 * The interesting half is not the value, it is everything that stops there
 * being one. A rider who asks for tyre pressure on a wheel that has no sensor,
 * or for a split with splits switched off, gets an answer either way: what is
 * missing, and what to do about it. Silence is the one response that teaches
 * them nothing, and the blank CONSUMPTION tile is what that looks like when it
 * goes wrong.
 *
 * Formatting the value stays with the caller, which already knows the rider's
 * units. This decides only between speaking a value and explaining why there is
 * none, so it needs no Android and can be tested directly.
 */
object VoiceAnswer {

    /** Why a question cannot be answered right now. */
    enum class Reason {
        /** The feature exists but the rider has it switched off. */
        OFF_IN_SETTINGS,

        /** This wheel does not report it at all. */
        UNSUPPORTED_BY_WHEEL,

        /** It will arrive, it just has not yet. No GPS fix, no trip started. */
        NO_DATA_YET,

        /** It needs something from the rider first, such as a pack size. */
        NEEDS_SETUP,
    }

    sealed interface Answer {
        /** Speak this. */
        data class Say(val name: String, val value: String) : Answer

        /**
         * Speak this sentence as it stands.
         *
         * The periodic reports already phrase Speed, Battery, Amps and the
         * rest in the rider's language and units, so a question about one
         * borrows that sentence whole rather than re-deriving it. It carries
         * its own name, which is why it is not a [Say]: prefixing it would
         * say "Battery, battery 45 percent".
         */
        data class SayReport(val name: String, val text: String) : Answer

        /** Explain why not, and what would fix it. */
        data class Unavailable(val name: String, val reason: Reason) : Answer

        /**
         * Do something, rather than say something.
         *
         * [confirm] is set for the few that are hard to undo: stopping a
         * recording throws away the rest of a ride, and a misheard word should
         * not be able to do that. Lights and the horn carry no such cost, and
         * asking twice for them would make the feature tiring.
         */
        data class Act(val key: String, val name: String, val confirm: Boolean) : Answer

        /** The rider asked what they can say. Offer a few real names. */
        data class Examples(val names: List<String>) : Answer

        /**
         * Nothing in the phrase was recognised.
         *
         * Carries the word that opens the list rather than two metric names.
         * Naming two metrics was oddly specific: a rider who said something
         * unrecognised is not helped by being told about Battery, they need
         * the way to find out what does work.
         */
        data class NotUnderstood(val helpPhrase: String) : Answer

        /** Several things were equally plausible; ask which. */
        data class NeedsChoice(val names: List<String>) : Answer
    }

    /**
     * @param term         what the matcher decided was asked for
     * @param value        the formatted value, or null when there is none
     * @param unavailable  why there is no value, when [value] is null
     * @param reportText   the sentence a spoken report already built for this
     *                     term, when it has one
     */
    fun answerFor(
        term: SpokenTerm,
        value: String?,
        unavailable: Reason?,
        reportText: String? = null,
    ): Answer {
        // A reason always wins over a value. A metric can hold a stale reading
        // from before the sensor dropped out, and reading that back as current
        // is worse than saying the sensor is gone.
        if (unavailable != null) return Answer.Unavailable(term.name, unavailable)
        if (!reportText.isNullOrBlank()) return Answer.SayReport(term.name, reportText)
        if (value.isNullOrBlank()) return Answer.Unavailable(term.name, Reason.NO_DATA_YET)
        return Answer.Say(term.name, value)
    }

    /**
     * Three things a rider could ask for, for when they asked what they can
     * ask for.
     *
     * Drawn from their own dashboard first, same as [notUnderstood], because
     * the tiles they chose are the values they care about. Three rather than
     * the whole catalog: this is spoken, usually while moving.
     */
    fun examples(vocabulary: List<SpokenTerm>, onDashboard: Set<String>): Answer {
        val askable = vocabulary.filter { it.kind != VoiceVocabulary.Kind.HELP }
        val preferred = askable.filter { it.key in onDashboard }
        val source = preferred.ifEmpty { askable }
        return Answer.Examples(source.take(3).map { it.name })
    }

    /**
     * What to say when nothing matched: the one word that opens the list.
     *
     * Spoken in the rider's language, because the help phrases are translated
     * and telling an Italian rider to say "help" would be telling them to say
     * a word the matcher will not be listening for.
     */
    fun notUnderstood(vocabulary: List<SpokenTerm>, onDashboard: Set<String>): Answer {
        val help = vocabulary.firstOrNull { it.kind == VoiceVocabulary.Kind.HELP }
        return Answer.NotUnderstood(help?.name.orEmpty())
    }
}
