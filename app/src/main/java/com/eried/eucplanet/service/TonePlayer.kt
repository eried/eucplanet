package com.eried.eucplanet.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.eried.eucplanet.data.repository.RouteKeepAlive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

class TonePlayer : RouteKeepAlive {

    private companion object {
        const val TAG = "TonePlayer"
    }

    // The samples come from the shared ToneSynth, so a beep is the same
    // waveform on both phones; this class plays them through AudioTrack.
    private val sampleRate = ToneSynth.SAMPLE_RATE

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
    //
    // The ride's hold rests after [rideQuietMs] with no sound: a silent track held all
    // ride long kept the audio path (and a Bluetooth headset link) awake for nothing.
    // The next sound plays into a cold route and warms it again for the next one.
    @Volatile private var keepAliveRide = false
    @Volatile private var keepAlivePreview = false
    @Volatile private var rideQuietMs = 120_000L
    @Volatile private var lastSoundMs = 0L
    private var keepAliveThread: Thread? = null
    private val keepAliveWanted get() = routeKeepAliveWanted(
        keepAlivePreview, keepAliveRide, System.currentTimeMillis(), lastSoundMs, rideQuietMs
    )

    /** A beep, stream or voice line is starting: the route is wanted again. */
    @Synchronized
    fun noteSound() { lastSoundMs = System.currentTimeMillis(); ensureKeepAlive() }

    /**
     * Queue one finished buffer and wait for it to actually play out.
     *
     * Shared by the beep and the note figure. The stop and release are in a
     * finally for a reason that is safety critical rather than tidy: a leaked
     * AudioTrack accumulates until every later build fails, and what fails
     * then is the alarms.
     */
    private fun playSamples(samples: ShortArray, totalN: Int, what: String) {
        noteSound()
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
    suspend fun playNotes(
        notes: List<Pair<Int, Int>>,
        gapMs: Int = 40,
        leadPadMs: Int = 30,
        volumePct: Int = 100,
    ) {
        if (notes.isEmpty()) return
        withContext(Dispatchers.IO) {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val minFrames = if (minBuf > 0) minBuf / 2 else 0
            val r = ToneSynth.renderNotes(notes, gapMs, leadPadMs, volumePct, minFrames)
                ?: return@withContext
            playSamples(r.samples, r.frames, "notes")
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

    /**
     * Play [count] beeps of [durationMs] at [frequencyHz], separated by [gapMs]
     * of silence, at [volumePct] of the media volume, rendered into one
     * AudioTrack buffer. Each run gets a raised-cosine attack and release of
     * [transitionMs] (below 0 means half the beep). With gap 0 the beeps merge
     * into one run of duration x count that ramps up once and down once.
     */
    suspend fun playBeep(
        frequencyHz: Int,
        durationMs: Int,
        count: Int = 1,
        gapMs: Int = 120,
        volumePct: Int = 100,
        transitionMs: Int = -1,
        waveform: Int = 0,
        effect: Int = 0,
    ) {
        if (count <= 0 || durationMs <= 0) return
        Log.d(TAG, "playBeep freq=$frequencyHz dur=$durationMs count=$count gap=$gapMs vol=$volumePct")
        withContext(Dispatchers.IO) {
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
            val r = ToneSynth.renderBeep(
                frequencyHz, durationMs, count, gapMs, volumePct, transitionMs,
                waveform, effect, glideToHz = 0, leadPadMs = 30, minFrames = minFrames,
            ) ?: return@withContext

            playSamples(r.samples, r.frames, "beep")
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
    override fun startRouteKeepAlive(quietMs: Long) {
        keepAliveRide = true; rideQuietMs = quietMs; lastSoundMs = System.currentTimeMillis()
        ensureKeepAlive()
    }

    /** Release the ride's hold. The route stays warm if a preview still wants it. */
    @Synchronized
    override fun stopRouteKeepAlive() { keepAliveRide = false }

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
            Log.i(TAG, "route warm")
            while (keepAliveWanted) {
                track.write(silence, 0, silence.size)   // blocks, pacing the loop
            }
        } catch (_: Exception) {
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            Log.i(TAG, "route rests")
            synchronized(this) {
                keepAliveThread = null
                ensureKeepAlive()   // a request that arrived during teardown respawns us
            }
        }
    }

    // --- Continuous streaming tone (Beep Studio preview and constant-tone alarms) ---
    // A persistent MODE_STREAM track fed by a producer thread, so the studio's
    // adaptive beep plays GAPLESSLY and glides as the rider drags the sliders,
    // instead of re-firing a fresh one-shot track (with its teardown gap) every
    // ~80 ms. Params are read live each buffer; the sine phase accumulates across
    // buffers so a pitch change is a smooth glide. With a gap, each on-period gets
    // the same raised-cosine swell as playBeep. With gap 0 it is one steady tone
    // that fades in once.
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
        noteSound()
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
        noteSound()  // the quiet window starts when a long alarm tone ends
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
        val synth = ToneSynth.Stream()
        try {
            track.play()
            while (streamOn) {
                // Snapshot the live params once per buffer.
                synth.fill(buf, sFreqHz, sDurMs, sGapMs, sVolPct, sTransMs, sWave, sEffect)
                track.write(buf, 0, bufFrames)   // blocks, pacing the loop
            }
            // Fade out over ~8 ms so stopping the track doesn't click.
            val fadeBuf = synth.fadeOut(sFreqHz, sVolPct)
            runCatching { track.write(fadeBuf, 0, fadeBuf.size) }
        } catch (_: Exception) {
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }
}

/** A preview always holds the route; a ride holds it until [quietMs] pass with no sound. */
internal fun routeKeepAliveWanted(
    preview: Boolean, ride: Boolean, nowMs: Long, lastSoundMs: Long, quietMs: Long,
): Boolean = preview || (ride && nowMs - lastSoundMs < quietMs)
