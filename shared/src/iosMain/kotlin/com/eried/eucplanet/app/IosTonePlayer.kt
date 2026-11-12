package com.eried.eucplanet.app

import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.service.ToneSynth
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.Foundation.NSLock
import kotlin.coroutines.resume

/**
 * The app's tones on iOS: the shared ToneSynth samples played through one
 * AVAudioEngine player node. The counterpart of Android's TonePlayer, which
 * writes the same samples to an AudioTrack. As there, a tone plays over the
 * rider's music without lowering or stopping it (Android's tones ask for no
 * audio focus). The audio session is held through [IosAudioSession] only
 * while tones play, and a short while after so a burst of beeps does not
 * stop and start the engine each time; then the engine stops and the hold
 * goes, so the session is let go once nothing else holds it.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosTonePlayer {
    private const val TAG = "TonePlayer"
    private val engine = AVAudioEngine()
    private val node = AVAudioPlayerNode()
    private val format = AVAudioFormat(standardFormatWithSampleRate = ToneSynth.SAMPLE_RATE.toDouble(), channels = 1u)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    // The session hold: taken by the first tone, given back [LINGER_MS] after
    // the last one ends (no figure playing, no studio stream).
    private const val LINGER_MS = 1500L
    private val lock = NSLock()
    private var held = false
    private var playing = 0
    private var lingerJob: Job? = null

    init {
        engine.attachNode(node)
        engine.connect(node, engine.mainMixerNode, format)
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    /** Holds the session and runs the engine; false when it would not start. */
    private fun ensureStarted(): Boolean = locked {
        lingerJob?.cancel()
        lingerJob = null
        if (!held) {
            IosAudioSession.acquire(IosAudioSession.Others.LEAVE)
            held = true
        } else if (!engine.running) {
            // Held, but the engine stopped under us (an interruption, a route
            // change): the session needs activating again before it restarts.
            IosAudioSession.reassert()
        }
        if (started && engine.running) return@locked true
        started = engine.startAndReturnError(null)
        if (started) node.play() else Log.e(TAG, "audio engine did not start")
        started
    }

    /** A figure or the stream is done: let the session go once nothing plays for a while. */
    private fun scheduleRelease() = locked {
        if (playing > 0 || streamJob?.isActive == true || !held) return@locked
        lingerJob?.cancel()
        lingerJob = scope.launch {
            delay(LINGER_MS)
            locked {
                if (playing > 0 || streamJob?.isActive == true || !held) return@locked
                // Stop the engine first: letting the session go with it still
                // running fails as busy and would leave the others lowered.
                node.stop()
                engine.stop()
                started = false
                held = false
                IosAudioSession.release(IosAudioSession.Others.LEAVE)
            }
        }
    }

    private fun toBuffer(samples: ShortArray, frames: Int): AVAudioPCMBuffer {
        val buf = AVAudioPCMBuffer(format, frames.toUInt())!!
        buf.frameLength = frames.toUInt()
        val ch = buf.floatChannelData!![0]!!
        for (i in 0 until frames) ch[i] = samples[i] / 32768f
        return buf
    }

    /** Plays one rendered figure and returns once it has played out. */
    suspend fun play(rendered: ToneSynth.Rendered) {
        locked { playing++ }
        try {
            if (!ensureStarted()) return
            val buf = toBuffer(rendered.samples, rendered.frames)
            suspendCancellableCoroutine { cont ->
                node.scheduleBuffer(buf) { if (cont.isActive) cont.resume(Unit) }
            }
        } finally {
            locked { playing-- }
            scheduleRelease()
        }
    }

    // --- The Beep Studio's endless tone ---
    private var streamJob: Job? = null
    private class Params(
        val freq: Int, val dur: Int, val gap: Int, val vol: Int, val trans: Int, val wave: Int, val effect: Int,
    )
    @kotlin.concurrent.Volatile private var params = Params(1000, 300, 100, 100, -1, 0, 0)

    fun startStream(freq: Int, dur: Int, gap: Int, vol: Int, trans: Int, wave: Int, effect: Int) {
        updateStream(freq, dur, gap, vol, trans, wave, effect)
        if (streamJob?.isActive == true) return
        if (!ensureStarted()) { scheduleRelease(); return }
        streamJob = scope.launch {
            val synth = ToneSynth.Stream()
            // Three ~12 ms buffers in flight: gapless, and a slider move is heard
            // within a few buffers, as on Android.
            val done = Channel<Unit>(Channel.UNLIMITED)
            repeat(3) { done.trySend(Unit) }
            val frames = 512
            while (isActive) {
                done.receive()
                val p = params
                val s = ShortArray(frames)
                synth.fill(s, p.freq, p.dur, p.gap, p.vol, p.trans, p.wave, p.effect)
                node.scheduleBuffer(toBuffer(s, frames)) { done.trySend(Unit) }
            }
        }
    }

    fun updateStream(freq: Int, dur: Int, gap: Int, vol: Int, trans: Int, wave: Int, effect: Int) {
        params = Params(freq, dur.coerceAtLeast(1), gap.coerceAtLeast(0), vol, trans, wave, effect)
    }

    fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        scheduleRelease()
    }
}
