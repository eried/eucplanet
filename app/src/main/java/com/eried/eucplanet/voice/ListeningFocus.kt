package com.eried.eucplanet.voice

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.eried.eucplanet.data.model.VoiceCommandSettings

/**
 * The rider's music, paused or turned down for one voice session.
 *
 * Held from the moment the microphone opens until the answer has been said,
 * as one request, so the song does not come back for a moment between the
 * question and the reply. The speaking voice still takes its own focus for
 * the sentence; this one sits under it and keeps the music down in the gaps.
 *
 * Each [hold] starts a new generation. A session that was dropped for a fresh
 * one (an ask while listening) releases with its old number, which no longer
 * matches, so it cannot hand the music back in the middle of the new session.
 */
class ListeningFocus(private val audioManager: AudioManager) {

    private var request: AudioFocusRequest? = null
    private var gain = 0
    private var generation = 0

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Take the music down for [mode]; returns this session's number for [release]. */
    @Synchronized
    fun hold(mode: String): Int {
        generation++
        val want = when (mode) {
            VoiceCommandSettings.MUSIC_PAUSE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            VoiceCommandSettings.MUSIC_DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            else -> 0
        }
        // Already held the same way, as when a session restarts: keep it, a
        // release and a new request would let the music up in between.
        if (want != 0 && request != null && gain == want) return generation
        abandon()
        if (want != 0) {
            val req = AudioFocusRequest.Builder(want)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
            if (audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                request = req
                gain = want
            }
        }
        return generation
    }

    /** Give the music back, if [session] is still the one holding it. */
    @Synchronized
    fun release(session: Int) {
        if (session == generation) abandon()
    }

    /** Give the music back whichever session took it, for a stop. */
    @Synchronized
    fun releaseAll() {
        generation++
        abandon()
    }

    private fun abandon() {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
        gain = 0
    }
}
