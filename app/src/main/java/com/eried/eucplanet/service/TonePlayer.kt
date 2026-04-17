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

    suspend fun playBeep(frequencyHz: Int, durationMs: Int, count: Int = 1) {
        withContext(Dispatchers.IO) {
            val gapMs = 120
            for (i in 0 until count) {
                playTone(frequencyHz, durationMs)
                if (i < count - 1) {
                    Thread.sleep(gapMs.toLong())
                }
            }
        }
    }

    private fun playTone(frequencyHz: Int, durationMs: Int) {
        val numSamples = sampleRate * durationMs / 1000
        val samples = ShortArray(numSamples)
        val twoPiF = 2.0 * Math.PI * frequencyHz / sampleRate

        // Generate sine wave with fade-in/fade-out to avoid clicks
        val fadeLen = (numSamples * 0.05).toInt().coerceAtLeast(100)
        for (i in 0 until numSamples) {
            var amplitude = sin(twoPiF * i)
            // Fade envelope
            if (i < fadeLen) amplitude *= i.toDouble() / fadeLen
            else if (i > numSamples - fadeLen) amplitude *= (numSamples - i).toDouble() / fadeLen
            samples[i] = (amplitude * Short.MAX_VALUE * 0.8).toInt().toShort()
        }

        val bufferSize = samples.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
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
        Thread.sleep(durationMs.toLong() + 50)
        track.stop()
        track.release()
    }
}
