package com.eried.eucplanet.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sin

@Singleton
class TonePlayer @Inject constructor() {

    private val sampleRate = 44100

    /**
     * Play [count] beeps of [durationMs] at [frequencyHz], separated by [gapMs]
     * of silence, at [volumePct] of the media volume.
     *
     * The whole pattern (tones plus gaps) is rendered into ONE AudioTrack buffer.
     * The sine phase runs continuously across contiguous tones and a short
     * click-guard fade is applied only where a tone meets silence (the very
     * start, the very end, and each gap). So gap = 0 is a single seamless tone
     * (an "almost constant" beep) instead of a string of separate beeps, and
     * short durations stay clean. A prior version built, played, and released a
     * fresh AudioTrack per beep, so the teardown plus a fade-out into every beep
     * left an audible gap even at gap = 0.
     */
    suspend fun playBeep(
        frequencyHz: Int,
        durationMs: Int,
        count: Int = 1,
        gapMs: Int = 120,
        volumePct: Int = 100,
    ) {
        if (count <= 0 || durationMs <= 0) return
        withContext(Dispatchers.IO) {
            val toneN = (sampleRate.toLong() * durationMs / 1000).toInt()
            if (toneN <= 0) return@withContext
            val gapN = (sampleRate.toLong() * gapMs.coerceAtLeast(0) / 1000).toInt()
            val totalN = toneN * count + gapN * (count - 1).coerceAtLeast(0)
            val samples = ShortArray(totalN)
            val twoPiF = 2.0 * Math.PI * frequencyHz / sampleRate
            // 0.8 = app-side headroom; volumePct scales under the system media
            // volume ceiling (Android applies that on top, so we can't exceed it).
            val gain = 0.8 * (volumePct.coerceIn(0, 100) / 100.0)
            // Click-guard fade length, applied only at a silence boundary.
            val edge = (toneN * 0.05).toInt().coerceIn(1, (toneN / 2).coerceAtLeast(1))

            var w = 0       // write cursor into samples
            var phase = 0   // sine phase; stays continuous across contiguous tones
            for (b in 0 until count) {
                val silenceBefore = b == 0 || gapN > 0
                val silenceAfter = b == count - 1 || gapN > 0
                for (i in 0 until toneN) {
                    var amp = sin(twoPiF * phase)
                    if (silenceBefore && i < edge) amp *= i.toDouble() / edge
                    else if (silenceAfter && i >= toneN - edge) amp *= (toneN - i).toDouble() / edge
                    samples[w++] = (amp * Short.MAX_VALUE * gain).toInt().toShort()
                    phase++
                }
                if (b < count - 1 && gapN > 0) {
                    w += gapN   // leave zeros (silence); restart phase after the gap
                    phase = 0
                }
                // gapN == 0: tones stay contiguous and the phase keeps running, so
                // the seam between them is a single uninterrupted sine.
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(samples, 0, samples.size)
            track.play()
            val playMs = totalN.toLong() * 1000 / sampleRate
            // Wait for the frames to actually PLAY OUT, not just for the nominal
            // buffer duration. play() returns immediately but the audio starts
            // later - the phone speaker adds a little, Bluetooth adds ~100-200ms -
            // so a fixed sleep followed by stop() (which halts a STATIC track at
            // once) chopped the tail into "half a beep" over BT. Poll the playback
            // head until every frame has been emitted, with a hard cap so a stalled
            // route can never hang the coroutine.
            val capNs = System.nanoTime() + (playMs + 2000L) * 1_000_000L
            while (track.playbackHeadPosition < totalN &&
                track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                System.nanoTime() < capNs
            ) {
                Thread.sleep(10)
            }
            Thread.sleep(30)   // small drain margin after the last frame
            track.stop()
            track.release()
        }
    }
}
