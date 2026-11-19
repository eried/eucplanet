package com.eried.eucplanet.voice

/**
 * The rider's voice language, as a tag a recogniser will accept.
 *
 * The setting stores Java's own `Locale.toString()` form, "en_US" with an
 * underscore, because that is what the text-to-speech picker hands back.
 * `RecognizerIntent.EXTRA_LANGUAGE` wants the IETF form, "en-US". Passing the
 * stored value straight through is the kind of mistake that does not throw: the
 * recogniser quietly falls back to the device language, so a Spanish rider asks
 * for "consumo" and is understood in English, or not at all.
 *
 * Pure, so the conversion is a unit test rather than something spotted in a log.
 */
object VoiceLocaleTag {

    /** Default when the rider has never chosen a voice. */
    const val FALLBACK = "en-US"

    fun tag(stored: String): String {
        val trimmed = stored.trim()
        if (trimmed.isEmpty()) return FALLBACK
        return trimmed.replace('_', '-')
    }
}
