package com.eried.eucplanet.audio

import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance

/** iOS text-to-speech via AVSpeechSynthesizer. */
private class IosSpeaker : Speaker {
    private val synth = AVSpeechSynthesizer()
    override var rate: Float = 0.5f

    override fun speak(text: String) {
        if (text.isBlank()) return
        val utterance = AVSpeechUtterance(string = text)
        utterance.rate = rate.coerceIn(0f, 1f)
        synth.speakUtterance(utterance)
    }

    override fun stop() {
        synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }
}

actual fun createSpeaker(): Speaker = IosSpeaker()
