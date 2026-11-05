package com.eried.eucplanet.app

import com.eried.eucplanet.service.ToneSynth
import com.eried.eucplanet.service.AlarmOutputs
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate

/**
 * What an alarm does on iOS: the shared tones through IosTonePlayer, the
 * voice through AVSpeechSynthesizer, and a buzz. No watch or car display on
 * iOS yet.
 */
internal object IosAlarmOutputs : AlarmOutputs {
    override suspend fun playBeep(
        frequencyHz: Int, durationMs: Int, count: Int, gapMs: Int, volumePct: Int,
        transitionMs: Int, waveform: Int, effect: Int,
    ) {
        if (count <= 0 || durationMs <= 0) return
        val r = ToneSynth.renderBeep(
            frequencyHz, durationMs, count, gapMs, volumePct, transitionMs,
            waveform, effect, glideToHz = 0, leadPadMs = 30, minFrames = 0,
        ) ?: return
        IosTonePlayer.play(r)
    }

    // AVAudioEngine has no route to keep warm the way Android's AudioTrack does.
    override fun setPreviewKeepAlive(on: Boolean) = Unit
    override fun startStream(frequencyHz: Int, durationMs: Int, gapMs: Int, volumePct: Int, transitionMs: Int, waveform: Int, effect: Int) =
        IosTonePlayer.startStream(frequencyHz, durationMs, gapMs, volumePct, transitionMs, waveform, effect)
    override fun updateStream(frequencyHz: Int, durationMs: Int, gapMs: Int, volumePct: Int, transitionMs: Int, waveform: Int, effect: Int) =
        IosTonePlayer.updateStream(frequencyHz, durationMs, gapMs, volumePct, transitionMs, waveform, effect)
    override fun stopStream() = IosTonePlayer.stopStream()

    // The alarm's own text, at the voice settings' rate and language.
    override fun speak(text: String) = IosAppGraph.voice.announceEvent(text)

    override fun vibrate(durationMs: Long) = AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    override fun vibrateWatch(durationMs: Int) = Unit
    override fun notifyCar(title: String, text: String) = Unit
}
