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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import kotlin.coroutines.resume

/**
 * The app's tones on iOS: the shared ToneSynth samples played through one
 * AVAudioEngine player node. The counterpart of Android's TonePlayer, which
 * writes the same samples to an AudioTrack. Tones duck other audio rather
 * than stop it, so a rider's music comes back after the beep.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosTonePlayer {
    private const val TAG = "TonePlayer"
    private val engine = AVAudioEngine()
    private val node = AVAudioPlayerNode()
    private val format = AVAudioFormat(standardFormatWithSampleRate = ToneSynth.SAMPLE_RATE.toDouble(), channels = 1u)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    init {
        engine.attachNode(node)
        engine.connect(node, engine.mainMixerNode, format)
    }

    private fun ensureStarted(): Boolean {
        if (started && engine.running) return true
        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionCategoryOptionDuckOthers, null)
            session.setActive(true, null)
        }
        started = engine.startAndReturnError(null)
        if (started) node.play() else Log.e(TAG, "audio engine did not start")
        return started
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
        if (!ensureStarted()) return
        val buf = toBuffer(rendered.samples, rendered.frames)
        suspendCancellableCoroutine { cont ->
            node.scheduleBuffer(buf) { if (cont.isActive) cont.resume(Unit) }
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
        if (streamJob?.isActive == true || !ensureStarted()) return
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
    }
}
