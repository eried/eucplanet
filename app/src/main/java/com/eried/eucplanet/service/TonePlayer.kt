package com.eried.eucplanet.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

@Singleton
class TonePlayer @Inject constructor() {

    private val sampleRate = 44100

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
    suspend fun playBeep(
        frequencyHz: Int,
        durationMs: Int,
        count: Int = 1,
        gapMs: Int = 120,
        volumePct: Int = 100,
        transitionMs: Int = -1,
    ) {
        if (count <= 0 || durationMs <= 0) return
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
            val leadPadN = sampleRate * 30 / 1000
            val tailPadN = sampleRate * 12 / 1000
            val totalN = leadPadN + runN * runCount + gapN * (runCount - 1).coerceAtLeast(0) + tailPadN
            val samples = ShortArray(totalN)
            val twoPiF = 2.0 * Math.PI * frequencyHz / sampleRate
            // 0.8 = app-side headroom; volumePct scales under the system media
            // volume ceiling (Android applies that on top, so we can't exceed it).
            val gain = 0.8 * (volumePct.coerceIn(0, 100) / 100.0)
            // Raised-cosine ramp length (attack/release) from transitionMs; auto (< 0)
            // = half the base beep. Capped to half the run so it always fits.
            val edge = (if (transitionMs >= 0)
                (sampleRate.toLong() * transitionMs / 1000).toInt()
            else toneN / 2).coerceIn(1, (runN / 2).coerceAtLeast(1))

            var w = leadPadN  // write cursor; leading silence pad already skipped
            var phase = 0   // sine phase; continuous within a run
            for (b in 0 until runCount) {
                for (i in 0 until runN) {
                    // Raised-cosine attack over the first [edge] of the run and release
                    // over the last [edge]; flat sustain (env = 1) in between.
                    val ramp = minOf(
                        if (i < edge) i.toDouble() / edge else 1.0,
                        if (i >= runN - edge) (runN - i).toDouble() / edge else 1.0
                    )
                    val env = 0.5 - 0.5 * cos(Math.PI * ramp)
                    val amp = sin(twoPiF * phase) * env
                    samples[w++] = (amp * Short.MAX_VALUE * gain).toInt().toShort()
                    phase++
                }
                if (b < runCount - 1 && gapN > 0) {
                    w += gapN   // leave zeros (silence); restart phase after the gap
                    phase = 0
                }
            }

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
                // Whole buffer fits, so write() queues it once; MODE_STREAM drains
                // gracefully on stop() (unlike MODE_STATIC which halts the track dead).
                .setBufferSizeInBytes(maxOf(minBuf, samples.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track.write(samples, 0, samples.size)
            track.play()
            val playMs = totalN.toLong() * 1000 / sampleRate
            // Wait for the frames to actually PLAY OUT (BT adds ~100-200ms), with a
            // hard cap so a stalled route can't hang the coroutine.
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
    private var streamThread: Thread? = null

    @Synchronized
    fun startStream(frequencyHz: Int, durationMs: Int, gapMs: Int, volumePct: Int, transitionMs: Int = -1) {
        updateStream(frequencyHz, durationMs, gapMs, volumePct, transitionMs)
        if (streamOn) return
        streamOn = true
        streamThread = thread(name = "ToneStream", isDaemon = true) { streamLoop() }
    }

    fun updateStream(frequencyHz: Int, durationMs: Int, gapMs: Int, volumePct: Int, transitionMs: Int = -1) {
        sFreqHz = frequencyHz
        sDurMs = durationMs.coerceAtLeast(1)
        sGapMs = gapMs.coerceAtLeast(0)
        sVolPct = volumePct
        sTransMs = transitionMs
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
        var phase = 0.0   // sine phase in cycles; continuous across buffers
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
                    val s = sin(2.0 * Math.PI * phase) * env * gain
                    buf[k] = (s * Short.MAX_VALUE).toInt().toShort()
                    phase += inc
                    if (phase >= 1.0) phase -= 1.0
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
