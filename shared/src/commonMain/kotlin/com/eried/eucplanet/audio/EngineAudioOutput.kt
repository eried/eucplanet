package com.eried.eucplanet.audio

/**
 * The phone's audio output for the engine: an AudioTrack with audio focus on
 * Android (EngineAudioTrack in :app), an AVAudioEngine player node holding
 * the audio session on iOS (IosEngineAudio).
 */
interface EngineAudioOutput {
    /** Asks other apps' audio to make room (Android's transient may-duck focus). */
    fun requestFocus()

    /** Gives back what [requestFocus] took. Safe to call without it. */
    fun abandonFocus()

    /**
     * Opens a mono float output at [sampleRate] and starts a producer off the
     * main thread that asks [producer] for one [bufferFrames] buffer after
     * another until [stop]. False when the output did not open.
     */
    fun start(sampleRate: Int, bufferFrames: Int, producer: EngineProducer): Boolean

    /** Ends the producer and releases the output. Idempotent. */
    fun stop()

    /** True when audio goes to headphones, Bluetooth or USB, not the phone's speaker. */
    fun externalOutputActive(): Boolean
}

/** What the output's producer pulls from; [EngineSoundEngine] is the only one. */
interface EngineProducer {
    /** Renders the next buffer into [out]; false once the engine has stopped. */
    fun render(out: FloatArray): Boolean

    /** The output failed mid-stream; the engine counts as stopped so the next start opens it again. */
    fun failed()
}
