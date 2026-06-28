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

    suspend fun playBeep(
        frequencyHz: Int,
        durationMs: Int,
        count: Int = 1,
        gapMs: Int = 120,
        volumePct: Int = 100,
    ) {
        withContext(Dispatchers.IO) {
            for (i in 0 until count) {
                playTone(frequencyHz, durationMs, volumePct)
                if (i < count - 1 && gapMs > 0) {
                    Thread.sleep(gapMs.toLong())
                }
            }
        }
    }

    private fun playTone(frequencyHz: Int, durationMs: Int, volumePct: Int = 100) {
        val numSamples = sampleRate * durationMs / 1000
        val samples = ShortArray(numSamples)
        val twoPiF = 2.0 * Math.PI * frequencyHz / sampleRate

        // 0.8 = app-side headroom; volumePct scales under the system media volume
        // ceiling (Android applies that on top, so we can't exceed it).
        val gain = 0.8 * (volumePct.coerceIn(0, 100) / 100.0)
        // Generate sine wave with fade-in/fade-out to avoid clicks
        val fadeLen = (numSamples * 0.05).toInt().coerceAtLeast(100)
        for (i in 0 until numSamples) {
            var amplitude = sin(twoPiF * i)
            // Fade envelope
            if (i < fadeLen) amplitude *= i.toDouble() / fadeLen
            else if (i > numSamples - fadeLen) amplitude *= (numSamples - i).toDouble() / fadeLen
            samples[i] = (amplitude * Short.MAX_VALUE * gain).toInt().toShort()
        }

        val bufferSize = samples.size * 2
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
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(samples, 0, samples.size)
        track.play()
        Thread.sleep(durationMs.toLong() + 20)   // small tail so the tone finishes before stop()
        track.stop()
        track.release()
    }
}
