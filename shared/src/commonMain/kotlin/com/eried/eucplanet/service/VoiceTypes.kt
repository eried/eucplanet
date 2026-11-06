package com.eried.eucplanet.service

/**
 * One TTS language the phone offers. [localeId] is the Java Locale.toString()
 * form ("nb_NO"), the form settings store in voiceLocale.
 */
data class VoiceOption(val localeId: String, val displayName: String)

/**
 * One selectable TTS voice within a locale. A language often exposes several
 * (different speakers, plus higher-quality "network" voices). [name] is the
 * stable engine id we persist and apply with `tts.setVoice`; [index] is a
 * 1-based position for a friendly "Voice N" label; [networkRequired] flags the
 * online voices so the UI can tag them.
 */
data class VoiceChoice(
    val name: String,
    val localeTag: String,
    val index: Int,
    val networkRequired: Boolean,
    // Android Voice.getQuality(): QUALITY_VERY_LOW(100)..QUALITY_VERY_HIGH(500).
    // The UI turns >=HIGH into "high quality" and <=LOW into "basic".
    val quality: Int
)
