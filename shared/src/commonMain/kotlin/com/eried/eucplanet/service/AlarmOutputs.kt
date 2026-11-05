package com.eried.eucplanet.service

/**
 * Everything an alarm can do to the rider, for the alarm engine when one
 * fires and for the editor when it is auditioned: the tone player's beeps
 * and live stream, the voice, the phone's and the watch's vibration, and the
 * car display. Android hands it TonePlayer, VoiceService, VibratorHelper,
 * WatchVibrator and CarAlarmNotification.
 */
interface AlarmOutputs {
    suspend fun playBeep(
        frequencyHz: Int, durationMs: Int, count: Int, gapMs: Int, volumePct: Int,
        transitionMs: Int, waveform: Int, effect: Int,
    )
    fun setPreviewKeepAlive(on: Boolean)
    fun startStream(frequencyHz: Int, durationMs: Int, gapMs: Int, volumePct: Int, transitionMs: Int, waveform: Int, effect: Int)
    fun updateStream(frequencyHz: Int, durationMs: Int, gapMs: Int, volumePct: Int, transitionMs: Int, waveform: Int, effect: Int)
    fun stopStream()
    fun speak(text: String)
    fun vibrate(durationMs: Long)
    fun vibrateWatch(durationMs: Int)
    /** An alarm on the car display; throws when the car side refuses. */
    fun notifyCar(title: String, text: String)
}
