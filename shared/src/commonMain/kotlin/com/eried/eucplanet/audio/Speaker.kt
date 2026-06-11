package com.eried.eucplanet.audio

/**
 * Platform text-to-speech seam for ride announcements. iOS wraps
 * `AVSpeechSynthesizer`; Android keeps its existing `TextToSpeech` in `:app` for
 * now (shared stub). [rate] and [volume] are normalized 0..1.
 */
interface Speaker {
    var rate: Float
    var volume: Float
    /** Selected system-voice identifier; null/blank = the platform default voice. */
    var voiceId: String?
    fun speak(text: String)
    fun stop()
}

/** Platform-provided speaker. */
expect fun createSpeaker(): Speaker

/** A selectable text-to-speech voice. */
data class TtsVoice(val id: String, val name: String, val language: String)

/** The voices installed on the device (empty where TTS isn't shared, e.g. Android). */
expect fun availableTtsVoices(): List<TtsVoice>
