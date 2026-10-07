package com.eried.eucplanet.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

@Singleton
class TonePlayer @Inject constructor() {

    private companion object {
        const val TAG = "TonePlayer"
        const val TWO_PI = 2.0 * Math.PI
        const val FM_INDEX = 3.0       // FM modulation index (modulator runs at 2x carrier)
        const val DRIVE = 3.0          // waveshaper drive for the "Drive" effect
        const val SWEEP_RATIO = 3.0    // "Sweep" low-pass cutoff = current pitch * this
        const val SWEEP_Q = 0.4        // filter damping; lower = more resonance
        const val CRUSH_LEVELS = 8.0   // "Crush" quantization: round(x*L)/L, ~4-bit
    }

    private val sampleRate = 44100

    /**
     * One oscillator sample for [waveform] at carrier phase [ph] (cycles, 0..1);
     * [modPh] is the FM modulator phase (used only by waveform 4). Codes match
     * [com.eried.eucplanet.data.model.AlarmRule.beepWaveform]: 0=sine, 1=triangle,
     * 2=square, 3=saw, 4=FM.
     */
    private fun waveSample(waveform: Int, ph: Double, modPh: Double): Double {
        val f = ph - kotlin.math.floor(ph)
        return when (waveform) {
            1 -> 4.0 * kotlin.math.abs(f - 0.5) - 1.0                 // triangle
            2 -> if (f < 0.5) 1.0 else -1.0                          // square
            3 -> 2.0 * f - 1.0                                       // saw
            4 -> sin(TWO_PI * ph + FM_INDEX * sin(TWO_PI * modPh))   // FM (metallic)
            else -> sin(TWO_PI * ph)                                 // sine
        }
    }

    /** Mutable state for the stateful effects (filter + sample-hold). */
    private class Fx { var lo = 0.0; var band = 0.0; var held = 0.0; var ctr = 0 }

    /**
     * Apply [effect] to sample [x] at the current pitch [freq] (Hz), mutating [fx]
     * for the stateful ones. Codes match
     * [com.eried.eucplanet.data.model.AlarmRule.beepEffect]: 0=none, 1=drive,
     * 2=sweep (resonant low-pass tracking pitch), 3=crush (bitcrush).
     */
    private fun applyFx(effect: Int, x: Double, freq: Double, fx: Fx): Double = when (effect) {
        1 -> kotlin.math.tanh(DRIVE * x)
        2 -> {
            val fc = (freq * SWEEP_RATIO).coerceIn(300.0, 8000.0)
            val f = 2.0 * sin(Math.PI * fc / sampleRate)
            fx.lo += f * fx.band
            val hi = x - fx.lo - SWEEP_Q * fx.band
            fx.band += f * hi
            fx.lo
        }
        3 -> {
            val hold = maxOf(1, sampleRate / 6000)
            if (fx.ctr <= 0) { fx.held = Math.round(x * CRUSH_LEVELS) / CRUSH_LEVELS; fx.ctr = hold }
            fx.ctr--
            fx.held
        }
        else -> x
    }

    // Audio-route keep-alive: a persistent SILENT track that holds the phone's audio
    // output powered, so the first sound after a quiet stretch - a beep OR a TTS voice
    // line (e.g. "wheel disconnected") - doesn't carry the route power-up pop.
    // Independent of beep/voice playback, so it can't silence them; it just keeps the
    // route warm.
    //
    // Two independent reasons to hold it: an active ride (started on wheel-connect) and
    // preview playback in the alarm editor (wheel disconnected, route otherwise cold).
    // It runs while EITHER wants it, so neither owner pulls the route out from under
    // the other. Separate booleans, not a counter, so lifecycle events that don't pair
    // up perfectly can't leak the track on forever.
    @Volatile private var keepAliveRide = false
    @Volatile private var keepAlivePreview = false
    private var keepAliveThread: Thread? = null
    private val keepAliveWanted get() = keepAliveRide || keepAlivePreview

    /**
     * Play [count] beeps of [durationMs] at [frequencyHz], separated by [gapMs]
     * of silence, at [volumePct] of the media volume.
     *
     * The whole pattern (tones plus gaps) is rendered into ONE AudioTrack buffer.
     * Every beep gets a raised-cosine ATTACK and RELEASE of length [transitionMs]
     * (a smooth eased swell), applied to all beeps, not just the ones touching
     * silence. [transitionMs] < 0 means auto = half the beep, i.e. a pure swell
     * with no flat top; a smaller value leaves a flat sustain in the middle; the
     * ramp always guards against clicks. With gap = 0 the swells butt together
     * into one continuously undulating tone (smooth up and downs) with no clicks
     * and no silent gaps, instead of a flat constant tone or a string of separate
     * beeps. The sine phase runs continuously across contiguous tones.
     */
    /**
     * Several notes in a single buffer.
     *
     * One AudioTrack for the whole figure, not one per note. Two playBeep
     * calls back to back is two starts and two stops, and the amp transient in
     * the seam between them is louder than the notes at these frequencies.
     * Each note gets its own short raised-cosine ramp, and the gaps are
     * silence inside the same buffer, so there is no seam to click.
     *
     * @param notes    frequency in hertz to duration in milliseconds, in order
     * @param gapMs    silence between notes
     * @param leadPadMs silence before the first note. The listening cue needs
     *   a long one: the recogniser has just opened the microphone, which moves
     *   the device into a communication audio mode, and a tone started into
     *   that switch arrives with the switch audible underneath it.
     */
    /**
     * Queue one finished buffer and wait for it to actually play out.
     *
     * Shared by the beep and the note figure. The stop and release are in a
     * finally for a reason that is safety critical rather than tidy: a leaked
     * AudioTrack accumulates until every later build fails, and what fails
     * then is the alarms.
     */
    private fun playSamples(samples: ShortArray, totalN: Int, what: String) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = try {
            AudioTrack.Builder()
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
                .setBufferSizeInBytes(maxOf(minBuf, samples.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "$what: AudioTrack build failed", e)
            return
        }
        try {
            track.write(samples, 0, samples.size)
            track.play()
            val playMs = totalN.toLong() * 1000 / sampleRate
            val capNs = System.nanoTime() + (playMs + 2000L) * 1_000_000L
            while (track.playbackHeadPosition < totalN &&
                track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                System.nanoTime() < capNs
            ) {
                Thread.sleep(10)
            }
            Thread.sleep(30)
        } catch (e: Exception) {
            Log.e(TAG, "$what: playback failed", e)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    suspend fun playNotes(
        notes: List<Pair<Int, Int>>,
        gapMs: Int = 40,
        leadPadMs: Int = 30,
        volumePct: Int = 100,
    ) {
        if (notes.isEmpty()) return
        withContext(Dispatchers.IO) {
            val leadPadN = sampleRate * leadPadMs.coerceAtLeast(0) / 1000
            val tailPadN = sampleRate * 12 / 1000
            val gapN = sampleRate * gapMs.coerceAtLeast(0) / 1000
            val noteN = notes.map { (_, ms) -> (sampleRate.toLong() * ms / 1000).toInt() }
            if (noteN.any { it <= 0 }) return@withContext
            val bodyN = leadPadN + noteN.sum() + gapN * (notes.size - 1) + tailPadN
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val minFrames = if (minBuf > 0) minBuf / 2 else 0
            val totalN = maxOf(bodyN, minFrames)
            val samples = ShortArray(totalN)
            val gain = 0.8 * (volumePct.coerceIn(0, 100) / 100.0)

            var w = leadPadN
            for ((index, note) in notes.withIndex()) {
                val (hz, _) = note
                val n = noteN[index]
                // A quarter of the note, capped, so short notes still ramp and
                // long ones are not all ramp.
                val edge = (n / 4).coerceIn(1, maxOf(1, sampleRate * 12 / 1000))
                var phase = 0.0
                val inc = hz.toDouble() / sampleRate
                for (i in 0 until n) {
                    val ramp = minOf(
                        if (i < edge) i.toDouble() / edge else 1.0,
                        if (i >= n - edge) (n - i).toDouble() / edge else 1.0
                    )
                    val env = 0.5 - 0.5 * cos(Math.PI * ramp)
                    samples[w++] = (sin(2.0 * Math.PI * phase) * env * gain * Short.MAX_VALUE)
                        .toInt().toShort()
                    phase += inc
                    if (phase >= 1.0) phase -= 1.0
                }
                if (index < notes.size - 1) w += gapN
            }
            playSamples(samples, totalN, "notes")
        }
    }

    /**
     * The cue that the microphone is open: two quick notes going up.
     *
     * Rising and quick reads as an invitation. Nothing a wheel does sounds
     * like it, which matters: a single flat beep is easy to take for the wheel
     * warning about something.
     */
    suspend fun playPrompt() {
        playNotes(listOf(780 to 70, 1040 to 80), gapMs = 45, leadPadMs = 160)
    }

    /**
     * The cue that it has stopped listening: the same two notes, going down.
     *
     * The mirror of the prompt on purpose. A rider who has learned one has
     * learned the other, and a window that closes silently is the app going
     * quiet in a way that cannot be told from it still listening.
     */
    suspend fun playEndPrompt() {
        playNotes(listOf(1040 to 70, 780 to 80), gapMs = 45, leadPadMs = 20, volumePct = 75)
    }

    /**
     * The cue that nothing matched: two low notes, the second lower still.
     *
     * Deliberately below both the opening and closing chirps and slower than
     * either. A rider learns three sounds here at most, so they have to be
     * told apart at speed with wind noise over them, and pitch carries
     * further than rhythm. Down-and-down reads as a refusal in a way that a
     * single flat note does not.
     */
    suspend fun playErrorPrompt() {
        // 160ms of lead silence, the same as the opening chirp and for the
        // same reason: the track's first samples arrive while it is still
        // warming up and the route may still be switching, and whatever is
        // written into that window is heard as a scratch. This one needs it
        // more than the chirps do, not less, because 420Hz is a long
        // wavelength and a clipped start on a low note is far more audible
        // than on a high one.
        playNotes(listOf(420 to 110, 310 to 150), gapMs = 30, leadPadMs = 160, volumePct = 85)
    }

    suspend fun playBeep(
        frequencyHz: Int,
        durationMs: Int,
        count: Int = 1,
        gapMs: Int = 120,
        volumePct: Int = 100,
        transitionMs: Int = -1,
        waveform: Int = 0,
        effect: Int = 0,
        /**
         * Sweep to this frequency across the run, 0 for a steady tone.
         *
         * A rising cue used to be two playBeep calls back to back, which is
         * two AudioTracks, two starts and two stops: the seam between them
         * clicked, and at low frequencies the click is the loudest part of the
         * sound. One buffer that changes pitch has no seam.
         */
        glideToHz: Int = 0,
        /**
         * Silence written before the tone, milliseconds.
         *
         * Thirty is enough for a beep on a settled route. The listening cue is
         * not on a settled route: the recogniser has just opened the
         * microphone, which moves the device into a communication audio mode,
         * and a tone started into that transition arrives with the switch
         * still audible under it.
         */
        leadPadMs: Int = 30,
    ) {
        if (count <= 0 || durationMs <= 0) return
        Log.d(TAG, "playBeep freq=$frequencyHz dur=$durationMs count=$count gap=$gapMs vol=$volumePct")
        withContext(Dispatchers.IO) {
            val toneN = (sampleRate.toLong() * durationMs / 1000).toInt()
            if (toneN <= 0) return@withContext
            val gapN = (sampleRate.toLong() * gapMs.coerceAtLeast(0) / 1000).toInt()
            // gap 0 merges the `count` beeps into ONE continuous run of duration*count
            // (a single longer tone: "3 beeps at gap 0" = "3x duration"), consistent
            // across alarms. gap>0 keeps them as `count` separate runs. The raised-cosine
            // attack/release is applied per RUN, so gap 0 ramps up once and down once
            // instead of undulating.
            val gapless = gapN <= 0
            val runN = if (gapless) toneN * count else toneN
            val runCount = if (gapless) 1 else count
            // Lead-in silence so the audio route/amp power-up transient (a start-of-
            // playback pop) settles in silence before the tone ramps in; a small tail
            // pad drains cleanly before stop().
            val leadPadN = sampleRate * leadPadMs.coerceAtLeast(0) / 1000
            val tailPadN = sampleRate * 12 / 1000
            val bodyN = leadPadN + runN * runCount + gapN * (runCount - 1).coerceAtLeast(0) + tailPadN
            // MODE_STREAM only begins playing once the written frames reach its start
            // threshold, which defaults to the buffer size and can't drop below the
            // device minimum. A short tone whose whole buffer is under that minimum
            // never crosses the threshold, so playback never starts and the beep is
            // silent (reported as "no beep under ~90 ms"; the exact cutoff is the
            // device min buffer). Pad the tail with extra silence so we always write
            // at least a full min-buffer of frames.
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val minFrames = if (minBuf > 0) minBuf / 2 else 0   // 16-bit mono: 2 bytes/frame
            val totalN = maxOf(bodyN, minFrames)
            val samples = ShortArray(totalN)
            // 0.8 = app-side headroom; volumePct scales under the system media
            // volume ceiling (Android applies that on top, so we can't exceed it).
            val gain = 0.8 * (volumePct.coerceIn(0, 100) / 100.0)
            // Raised-cosine ramp length (attack/release) from transitionMs; auto (< 0)
            // = half the base beep. Capped to half the run so it always fits.
            val edge = (if (transitionMs >= 0)
                (sampleRate.toLong() * transitionMs / 1000).toInt()
            else toneN / 2).coerceIn(1, (runN / 2).coerceAtLeast(1))

            var w = leadPadN  // write cursor; leading silence pad already skipped
            val inc = frequencyHz.toDouble() / sampleRate
            val glideEnd = if (glideToHz > 0) glideToHz.toDouble() else frequencyHz.toDouble()
            var phase = 0.0       // carrier phase in cycles; continuous within a run
            var modPhase = 0.0    // FM modulator phase (runs at 2x carrier)
            val fx = Fx()
            for (b in 0 until runCount) {
                for (i in 0 until runN) {
                    // Raised-cosine attack over the first [edge] of the run and release
                    // over the last [edge]; flat sustain (env = 1) in between.
                    val ramp = minOf(
                        if (i < edge) i.toDouble() / edge else 1.0,
                        if (i >= runN - edge) (runN - i).toDouble() / edge else 1.0
                    )
                    val env = 0.5 - 0.5 * cos(Math.PI * ramp)
                    // Steady unless gliding, in which case the frequency walks
                    // from one end to the other across the run.
                    val freqNow = if (glideToHz > 0) {
                        frequencyHz + (glideEnd - frequencyHz) * (i.toDouble() / runN)
                    } else {
                        frequencyHz.toDouble()
                    }
                    val incNow = if (glideToHz > 0) freqNow / sampleRate else inc
                    val osc = applyFx(effect, waveSample(waveform, phase, modPhase), freqNow, fx)
                    samples[w++] = (osc * env * gain * Short.MAX_VALUE).toInt().toShort()
                    phase += incNow; if (phase >= 1.0) phase -= 1.0
                    modPhase += 2.0 * incNow; if (modPhase >= 1.0) modPhase -= 1.0
                }
                if (b < runCount - 1 && gapN > 0) {
                    w += gapN   // leave zeros (silence); restart phase + effect state after the gap
                    phase = 0.0; modPhase = 0.0
                    fx.lo = 0.0; fx.band = 0.0; fx.ctr = 0
                }
            }

            playSamples(samples, totalN, "beep")
        }
    }

    /**
     * Warm the route for a connected ride (idempotent). A separate silent MODE_STREAM
     * track runs on its own thread continuously writing zeros, which keeps
     * AudioFlinger's output stream open so the speaker/amp never drops to standby.
     * Called on wheel-connect; the first beep/voice after a quiet stretch then fires
     * into an already-powered route with no pop. Fully independent of playBeep / the
     * studio stream, so a failure here never silences an actual alarm.
     */
    @Synchronized
    fun startRouteKeepAlive() { keepAliveRide = true; ensureKeepAlive() }

    /** Release the ride's hold. The route stays warm if a preview still wants it. */
    @Synchronized
    fun stopRouteKeepAlive() { keepAliveRide = false }

    /** Warm the route while the alarm editor is open, so preview beeps/voice fired from
     *  a settings screen (wheel disconnected, route cold) don't pop. */
    @Synchronized
    fun setPreviewKeepAlive(on: Boolean) { keepAlivePreview = on; if (on) ensureKeepAlive() }

    private fun ensureKeepAlive() {
        if (keepAliveThread == null && keepAliveWanted) {
            keepAliveThread = thread(name = "ToneKeepAlive", isDaemon = true) { keepAliveLoop() }
        }
    }

    private fun keepAliveLoop() {
        val chunk = 512
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = try {
            AudioTrack.Builder()
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
                .setBufferSizeInBytes(maxOf(minBuf, chunk * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            synchronized(this) { keepAliveThread = null }   // no respawn: avoid a build-fail spin
            return
        }
        val silence = ShortArray(chunk)   // zeros: inaudible, just holds the route open
        try {
            track.play()
            while (keepAliveWanted) {
                track.write(silence, 0, silence.size)   // blocks, pacing the loop
            }
        } catch (_: Exception) {
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            synchronized(this) {
                keepAliveThread = null
                ensureKeepAlive()   // a request that arrived during teardown respawns us
            }
        }
    }

    // --- Continuous streaming tone (Beep Studio live preview) ---
    // A persistent MODE_STREAM track fed by a producer thread, so the studio's
    // adaptive beep plays GAPLESSLY and glides as the rider drags the sliders,
    // instead of re-firing a fresh one-shot track (with its teardown gap) every
    // ~80 ms. Params are read live each buffer; the sine phase accumulates across
    // buffers so a pitch change is a smooth glide, and the same raised-cosine swell
    // as playBeep runs on each on-period so gap = 0 is one undulating tone.
    @Volatile private var streamOn = false
    @Volatile private var sFreqHz = 1000
    @Volatile private var sDurMs = 300
    @Volatile private var sGapMs = 100
    @Volatile private var sVolPct = 100
    @Volatile private var sTransMs = -1
    @Volatile private var sWave = 0
    @Volatile private var sEffect = 0
    private var streamThread: Thread? = null

    @Synchronized
    fun startStream(frequencyHz: Int, durationMs: Int, gapMs: Int, volumePct: Int, transitionMs: Int = -1, waveform: Int = 0, effect: Int = 0) {
        updateStream(frequencyHz, durationMs, gapMs, volumePct, transitionMs, waveform, effect)
        if (streamOn) return
        streamOn = true
        streamThread = thread(name = "ToneStream", isDaemon = true) { streamLoop() }
    }

    fun updateStream(frequencyHz: Int, durationMs: Int, gapMs: Int, volumePct: Int, transitionMs: Int = -1, waveform: Int = 0, effect: Int = 0) {
        sFreqHz = frequencyHz
        sDurMs = durationMs.coerceAtLeast(1)
        sGapMs = gapMs.coerceAtLeast(0)
        sVolPct = volumePct
        sTransMs = transitionMs
        sWave = waveform
        sEffect = effect
    }

    @Synchronized
    fun stopStream() {
        if (!streamOn) return
        streamOn = false
        streamThread?.join(500)
        streamThread = null
    }

    private fun streamLoop() {
        val bufFrames = 512   // ~12 ms at 44.1k: live enough for slider drags
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
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
            .setBufferSizeInBytes(maxOf(minBuf, bufFrames * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val buf = ShortArray(bufFrames)
        var phase = 0.0   // carrier phase in cycles; continuous across buffers
        var modPhase = 0.0 // FM modulator phase (runs at 2x carrier)
        val fx = Fx()      // sweep filter + crush sample-hold, persists across buffers
        var pos = 0       // sample index within the current on+gap period
        var emitted = 0L  // total samples emitted (drives the gap-0 fade-in)
        try {
            track.play()
            while (streamOn) {
                // Snapshot the live params once per buffer.
                val inc = sFreqHz.toDouble() / sampleRate
                val durN = (sampleRate.toLong() * sDurMs / 1000).toInt().coerceAtLeast(1)
                val gapN = (sampleRate.toLong() * sGapMs / 1000).toInt().coerceAtLeast(0)
                val periodN = durN + gapN
                val gain = 0.8 * (sVolPct.coerceIn(0, 100) / 100.0)
                val edge = (if (sTransMs >= 0)
                    (sampleRate.toLong() * sTransMs / 1000).toInt()
                else durN / 2).coerceIn(1, (durN / 2).coerceAtLeast(1))
                val gapless = gapN <= 0
                for (k in 0 until bufFrames) {
                    if (pos >= periodN) pos = 0
                    val env = if (gapless) {
                        // gap 0 = one steady continuous tone; fade in once at the start.
                        if (emitted < edge) 0.5 - 0.5 * cos(Math.PI * emitted.toDouble() / edge) else 1.0
                    } else if (pos < durN) {
                        val ramp = minOf(
                            if (pos < edge) pos.toDouble() / edge else 1.0,
                            if (pos >= durN - edge) (durN - pos).toDouble() / edge else 1.0
                        )
                        0.5 - 0.5 * cos(Math.PI * ramp)
                    } else 0.0   // in the gap
                    val osc = applyFx(sEffect, waveSample(sWave, phase, modPhase), sFreqHz.toDouble(), fx)
                    val s = osc * env * gain
                    buf[k] = (s * Short.MAX_VALUE).toInt().toShort()
                    phase += inc
                    if (phase >= 1.0) phase -= 1.0
                    modPhase += 2.0 * inc
                    if (modPhase >= 1.0) modPhase -= 1.0
                    pos++
                    emitted++
                }
                track.write(buf, 0, bufFrames)   // blocks, pacing the loop
            }
            // Fade out over ~8 ms so stopping the track doesn't click.
            val fadeN = sampleRate * 8 / 1000
            val fadeBuf = ShortArray(fadeN)
            val fadeInc = sFreqHz.toDouble() / sampleRate
            val fadeGain = 0.8 * (sVolPct.coerceIn(0, 100) / 100.0)
            for (k in 0 until fadeN) {
                val env = 0.5 + 0.5 * cos(Math.PI * k.toDouble() / fadeN)   // 1 -> 0
                fadeBuf[k] = (sin(2.0 * Math.PI * phase) * env * fadeGain * Short.MAX_VALUE).toInt().toShort()
                phase += fadeInc
                if (phase >= 1.0) phase -= 1.0
            }
            runCatching { track.write(fadeBuf, 0, fadeN) }
        } catch (_: Exception) {
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }
}
