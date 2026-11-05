package com.eried.eucplanet.app

import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.service.AlarmOutputs
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate

/**
 * What an alarm does on iOS. The voice speaks and the phone buzzes; the tone
 * synthesizer (Android's TonePlayer) is not on iOS yet, so a beep is logged
 * instead. No watch or car display on iOS yet.
 */
internal object IosAlarmOutputs : AlarmOutputs {
    private val speech = AVSpeechSynthesizer()

    override suspend fun playBeep(
        frequencyHz: Int, durationMs: Int, count: Int, gapMs: Int, volumePct: Int,
        transitionMs: Int, waveform: Int, effect: Int,
    ) {
        Log.i("AlarmOutputs", "beep not on iOS yet: $frequencyHz Hz x$count")
    }

    override fun setPreviewKeepAlive(on: Boolean) = Unit
    override fun startStream(frequencyHz: Int, durationMs: Int, gapMs: Int, volumePct: Int, transitionMs: Int, waveform: Int, effect: Int) = Unit
    override fun updateStream(frequencyHz: Int, durationMs: Int, gapMs: Int, volumePct: Int, transitionMs: Int, waveform: Int, effect: Int) = Unit
    override fun stopStream() = Unit

    override fun speak(text: String) {
        speech.speakUtterance(AVSpeechUtterance.speechUtteranceWithString(text))
    }

    override fun vibrate(durationMs: Long) = AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    override fun vibrateWatch(durationMs: Int) = Unit
    override fun notifyCar(title: String, text: String) = Unit
}
