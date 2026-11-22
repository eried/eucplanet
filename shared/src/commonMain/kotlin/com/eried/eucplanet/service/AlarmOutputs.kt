package com.eried.eucplanet.service

/**
 * Everything an alarm can do to the rider, for the alarm engine when one
 * fires and for the editor when it is auditioned: the tone player's beeps
 * and live stream, the voice, and the phone's and the watch's vibration.
 * Android hands it TonePlayer, VoiceService, VibratorHelper and WatchVibrator.
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
}
