package com.eried.eucplanet.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.concurrent.thread

/**
 * The engine's output on Android: a streaming float AudioTrack on the media
 * usage, fed by a producer thread that writes blocking, and the transient
 * may-duck audio focus around it. The shared [EngineSoundEngine] drives it.
 */
class EngineAudioTrack(context: Context) : EngineAudioOutput {

    private val channelMask = AudioFormat.CHANNEL_OUT_MONO

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var producerThread: Thread? = null
    @Volatile private var running: Boolean = false

    // Audio focus
    private var focusRequest: AudioFocusRequest? = null

    override fun start(sampleRate: Int, bufferFrames: Int, producer: EngineProducer): Boolean {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(bufferFrames * 4)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
            running = true
            producerThread = thread(name = "EngineSoundProducer", isDaemon = true) {
                runProducer(producer, bufferFrames)
            }
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start engine audio", e)
            running = false
            return false
        }
    }

    override fun stop() {
        running = false
        try {
            producerThread?.join(500)
        } catch (_: InterruptedException) {}
        producerThread = null
        audioTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Stop/release threw", e)
            }
        }
        audioTrack = null
    }

    private fun runProducer(producer: EngineProducer, bufferFrames: Int) {
        val buffer = FloatArray(bufferFrames)
        while (running && producer.render(buffer)) {
            val written = audioTrack?.write(buffer, 0, bufferFrames, AudioTrack.WRITE_BLOCKING) ?: 0
            if (written < 0) {
                Log.w(TAG, "AudioTrack.write returned $written, stopping")
                running = false
                producer.failed()
            }
        }
    }

    override fun requestFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { /* mix with whatever the system says */ }
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "requestAudioFocus failed", e)
        }
    }

    override fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            }
        } catch (_: Throwable) {}
    }

    override fun externalOutputActive(): Boolean = AudioOutput.isExternalActive(audioManager)

    companion object {
        private const val TAG = "EngineSoundEngine"

        /** A recorded engine's grain bank from `res/raw`, or null when the resource is missing. */
        fun rawBank(context: Context): (String) -> ByteArray? = { asset ->
            val id = context.resources.getIdentifier(asset, "raw", context.packageName)
            if (id == 0) null else context.resources.openRawResource(id).use { it.readBytes() }
        }
    }
}
