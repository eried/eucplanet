@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.eried.eucplanet.audio

import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.setActive

/** iOS text-to-speech via AVSpeechSynthesizer. */
private class IosSpeaker : Speaker {
    private val synth = AVSpeechSynthesizer()
    override var rate: Float = 0.5f
    override var volume: Float = 1f
    override var voiceId: String? = null

    init {
        // Without an active, audible audio session AVSpeechSynthesizer is SILENT on a
        // real device (it works in the Simulator, which is why this only showed up on
        // hardware). Playback category = audible even with the ring/silent switch on;
        // DuckOthers lowers (doesn't stop) any music the rider is playing.
        runCatching {
            AVAudioSession.sharedInstance().setCategory(
                AVAudioSessionCategoryPlayback,
                withOptions = AVAudioSessionCategoryOptionDuckOthers,
                error = null,
            )
        }
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        // (Re)activate before each utterance — a phone call / another app may have
        // deactivated our session since the last one.
        runCatching { AVAudioSession.sharedInstance().setActive(true, error = null) }
        val utterance = AVSpeechUtterance(string = text)
        utterance.rate = rate.coerceIn(0f, 1f)
        utterance.volume = volume.coerceIn(0f, 1f)
        voiceId?.takeIf { it.isNotBlank() }?.let { id ->
            AVSpeechSynthesisVoice.voiceWithIdentifier(id)?.let { utterance.voice = it }
        }
        synth.speakUtterance(utterance)
    }

    override fun stop() {
        synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }
}

actual fun createSpeaker(): Speaker = IosSpeaker()

actual fun availableTtsVoices(): List<TtsVoice> =
    AVSpeechSynthesisVoice.speechVoices().mapNotNull { v ->
        (v as? AVSpeechSynthesisVoice)?.let { TtsVoice(it.identifier, it.name, it.language) }
    }
