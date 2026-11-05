package com.eried.eucplanet.ui.settings

import android.content.Context
import com.eried.eucplanet.service.TonePlayer
import com.eried.eucplanet.service.VoiceService
import com.eried.eucplanet.util.VibratorHelper

/** TonePlayer, VoiceService and the vibrator as the shared alarm editor's [AlarmPreview]. */
class AndroidAlarmPreview(
    context: Context,
    private val tonePlayer: TonePlayer,
    private val voiceService: VoiceService,
) : AlarmPreview {
    private val vibratorHelper = VibratorHelper(context)

    override suspend fun playBeep(
        frequencyHz: Int, durationMs: Int, count: Int, gapMs: Int, volumePct: Int,
        transitionMs: Int, waveform: Int, effect: Int,
    ) = tonePlayer.playBeep(frequencyHz, durationMs, count, gapMs, volumePct, transitionMs, waveform, effect)

    override fun setPreviewKeepAlive(on: Boolean) = tonePlayer.setPreviewKeepAlive(on)

    override fun startStream(frequencyHz: Int, durationMs: Int, gapMs: Int, volumePct: Int, transitionMs: Int, waveform: Int, effect: Int) =
        tonePlayer.startStream(frequencyHz, durationMs, gapMs, volumePct, transitionMs, waveform, effect)

    override fun updateStream(frequencyHz: Int, durationMs: Int, gapMs: Int, volumePct: Int, transitionMs: Int, waveform: Int, effect: Int) =
        tonePlayer.updateStream(frequencyHz, durationMs, gapMs, volumePct, transitionMs, waveform, effect)

    override fun stopStream() = tonePlayer.stopStream()

    override fun speak(text: String) = voiceService.speak(text)

    override fun vibrate(durationMs: Long) = vibratorHelper.oneShot(durationMs)
}
