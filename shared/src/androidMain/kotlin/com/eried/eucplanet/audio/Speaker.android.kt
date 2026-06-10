package com.eried.eucplanet.audio

/**
 * Android keeps using the existing `:app` `TextToSpeech` wiring (which has the
 * Context + audio-focus plumbing) for now; shared no-op stub so the module
 * compiles for the Android target. The shared speaker is used on iOS.
 */
private class NoopSpeaker : Speaker {
    override var rate: Float = 0.5f
    override fun speak(text: String) {}
    override fun stop() {}
}

actual fun createSpeaker(): Speaker = NoopSpeaker()
