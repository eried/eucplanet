package com.eried.eucplanet.service

import android.content.Context
import com.eried.eucplanet.car.CarAlarmNotification
import com.eried.eucplanet.util.VibratorHelper
import com.eried.eucplanet.wear.WatchVibrator

/** TonePlayer, VoiceService, the vibrators and the car display as the shared [AlarmOutputs]. */
class AndroidAlarmOutputs(
    private val context: Context,
    private val tonePlayer: TonePlayer,
    private val voiceService: VoiceService,
    private val watchVibrator: WatchVibrator,
) : AlarmOutputs {
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

    override fun vibrateWatch(durationMs: Int) = watchVibrator.vibrate(durationMs)

    override fun notifyCar(title: String, text: String) = CarAlarmNotification.post(context, title, text)
}
