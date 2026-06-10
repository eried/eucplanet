package com.eried.eucplanet.audio

/**
 * Platform text-to-speech seam for ride announcements. iOS wraps
 * `AVSpeechSynthesizer`; Android keeps its existing `TextToSpeech` in `:app` for
 * now (shared stub). [rate] and [volume] are normalized 0..1.
 */
interface Speaker {
    var rate: Float
    var volume: Float
    fun speak(text: String)
    fun stop()
}

/** Platform-provided speaker. */
expect fun createSpeaker(): Speaker
