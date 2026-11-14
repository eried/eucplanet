package com.eried.eucplanet.app

import com.eried.eucplanet.audio.EngineAudioOutput
import com.eried.eucplanet.audio.EngineProducer
import com.eried.eucplanet.audio.EngineSoundEngine
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.resources.Res
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.Foundation.NSError
import platform.Foundation.NSLock
import kotlin.concurrent.Volatile

/**
 * The engine sound's output on iOS, the counterpart of Android's
 * EngineAudioTrack. A producer coroutine off the main thread renders each
 * buffer with the shared synth and schedules it on an AVAudioPlayerNode, so
 * no Kotlin runs inside the real-time render callback. The buffers are made
 * once at start and handed back by their completion, so the hot path
 * allocates nothing, as on Android.
 *
 * While it plays it holds the audio session through [IosAudioSession] with
 * DUCK, Android's transient may-duck focus for the engine: the rider's music
 * goes down, the voice and the tones still sound over it (they hold the same
 * session, strongest wish wins), and the audio background mode keeps it
 * going with the screen locked.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal class IosEngineAudio : EngineAudioOutput {
    private val engine = AVAudioEngine()
    private val node = AVAudioPlayerNode()
    private var connectedRate = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = NSLock()
    private var held = false
    private var job: Job? = null
    @Volatile private var running = false

    /** Buffers scheduled since the app started, for the log and the tests. */
    @Volatile var scheduled: Long = 0L
        private set

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    override fun requestFocus() = locked {
        if (!held) {
            IosAudioSession.acquire(IosAudioSession.Others.DUCK)
            held = true
        }
    }

    override fun abandonFocus() = locked {
        if (held) {
            held = false
            IosAudioSession.release(IosAudioSession.Others.DUCK)
        }
    }

    override fun externalOutputActive(): Boolean = IosAudioSession.externalOutputActive()

    override fun start(sampleRate: Int, bufferFrames: Int, producer: EngineProducer): Boolean {
        val format = AVAudioFormat(standardFormatWithSampleRate = sampleRate.toDouble(), channels = 1u)
        if (connectedRate != sampleRate) {
            if (connectedRate == 0) engine.attachNode(node)
            engine.connect(node, engine.mainMixerNode, format)
            connectedRate = sampleRate
        }
        if (!engine.running && !startEngine()) {
            Log.e(TAG, "Failed to start engine audio")
            return false
        }
        node.play()
        running = true
        val pool = Array(IN_FLIGHT) {
            AVAudioPCMBuffer(format, bufferFrames.toUInt()).apply { frameLength = bufferFrames.toUInt() }
        }
        job = scope.launch {
            // Each buffer comes back here when the node has played it.
            val free = Channel<AVAudioPCMBuffer>(IN_FLIGHT)
            pool.forEach { free.trySend(it) }
            val scratch = FloatArray(bufferFrames)
            while (isActive && running) {
                val buf = withTimeoutOrNull(STALL_MS) { free.receive() }
                if (buf == null) {
                    // Nothing came back: an interruption or a route change stopped
                    // the engine under us. Take the session again and restart;
                    // stopping the node hands every scheduled buffer back.
                    Log.w(TAG, "output stalled, restarting")
                    IosAudioSession.reassert()
                    node.stop()
                    if (!engine.running && !startEngine()) {
                        Log.e(TAG, "audio engine did not restart, stopping")
                        running = false
                        producer.failed()
                        break
                    }
                    node.play()
                    continue
                }
                if (!producer.render(scratch)) break
                val ch = buf.floatChannelData!![0]!!
                for (i in 0 until bufferFrames) ch[i] = scratch[i]
                node.scheduleBuffer(buf) { free.trySend(buf) }
                val n = ++scheduled
                if (n == 1L || n % LOG_EVERY == 0L) {
                    var sum = 0f
                    for (v in scratch) sum += v * v
                    Log.i(TAG, "scheduled $n buffers, last rms ${(kotlin.math.sqrt(sum / bufferFrames) * 1000).toInt() / 1000f}")
                }
            }
        }
        return true
    }

    private fun startEngine(): Boolean = memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        val ok = engine.startAndReturnError(err.ptr)
        if (!ok) Log.w(TAG, "audio engine: ${err.value?.localizedDescription}")
        ok
    }

    override fun stop() {
        running = false
        val j = job
        job = null
        // Wait for the buffer being rendered, as Android joins its thread, so
        // the synth is not reset under it.
        if (j != null) runBlocking { withTimeoutOrNull(JOIN_MS) { j.cancelAndJoin() } }
        if (connectedRate != 0) {
            node.stop()
            engine.stop()
        }
    }

    companion object {
        private const val TAG = "EngineSound"
        /** Three buffers of 1024 frames in flight, ~70 ms: gapless, and a throttle change is heard within it. */
        private const val IN_FLIGHT = 3
        private const val STALL_MS = 1000L
        private const val JOIN_MS = 500L
        /** About every 23 s of sound. */
        private const val LOG_EVERY = 1000L

        /** A recorded engine's grain bank, bundled from app/src/main/res/raw as a Compose resource file. */
        fun bundledBank(asset: String): ByteArray? =
            runCatching { runBlocking { Res.readBytes("files/$asset.bin") } }.getOrNull()

        fun newEngine(): EngineSoundEngine = EngineSoundEngine(IosEngineAudio(), ::bundledBank)
    }
}
