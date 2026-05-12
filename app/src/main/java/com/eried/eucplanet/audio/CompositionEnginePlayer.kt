package com.eried.eucplanet.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs

/**
 * Multi-section playback for sampled engines. The procedural [SampledEnginePlayer]
 * plays a single OGG looped — this player composes the engine sound from named
 * sections inside one or more raw resources (the user picks them in deletelater.html).
 *
 * Sections used:
 *  - "idle_loop" — sustained low-RPM loop (required)
 *  - "rev_loop"  — sustained high-RPM loop (optional — without it, idle pitch-shifts up)
 *  - "startup"   — one-shot transient played on engine start (optional)
 *  - "decel"     — one-shot transient played when throttle closes sharply (optional)
 *  - "shutdown"  — one-shot transient played on engine stop (optional)
 *
 * The two looping players (idle, rev) play simultaneously with crossfaded gains.
 * One-shot players are spawned on demand for startup/decel/shutdown.
 *
 * Each section is clipped from its host file via `seekTo(startMs)` + a 50 ms position
 * poll that seeks back to startMs when the player crosses endMs. There's a ~50 ms
 * audible seam at the loop point — acceptable for v1; ExoPlayer ClippingMediaSource
 * would give us seamless looping when we upgrade.
 */
class CompositionEnginePlayer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var profile: EngineProfile? = null
    @Volatile private var idle: SectionPlayer? = null
    @Volatile private var rev: SectionPlayer? = null
    private val oneShots = mutableListOf<SectionPlayer>()

    @Volatile private var lastVolume: Float = 0f
    @Volatile private var lastRpmNorm: Float = 0f
    @Volatile private var lastIdleVol: Float = -1f
    @Volatile private var lastRevVol: Float = -1f
    @Volatile private var lastSpeed: Float = -1f
    // Smoothed actual volumes — drives both the engine-start fade-in (each starts
    // at 0 and ramps toward target) and the idle↔rev crossfade. Alpha 0.15 gives
    // a ~85 ms time constant at ~100 Hz telemetry, comfortable but not sluggish.
    @Volatile private var smoothedIdleVol: Float = 0f
    @Volatile private var smoothedRevVol: Float = 0f
    private val volSmoothingAlpha = 0.15f

    fun isPlaying(): Boolean = idle != null

    /** Starts both loops at zero volume. The next [update] call sets real gains by RPM. */
    fun start(profile: EngineProfile) {
        if (this.profile?.key == profile.key && idle != null) return
        stop()
        val sections = profile.sampleSections ?: return
        this.profile = profile

        sections["idle_loop"]?.let { sec ->
            idle = SectionPlayer(context, sec, looping = true).also {
                if (it.prepare()) it.play() else { Log.w(TAG, "idle_loop failed to prepare"); idle = null }
            }
        }
        sections["rev_loop"]?.let { sec ->
            rev = SectionPlayer(context, sec, looping = true).also {
                if (it.prepare()) it.play() else { Log.w(TAG, "rev_loop failed to prepare"); rev = null }
            }
        }
        // Engine start transient (fire and forget).
        sections["startup"]?.let { fireOneShot(it, gain = 1f) }
    }

    fun stop() {
        // Engine-off transient before we tear down.
        profile?.sampleSections?.get("shutdown")?.let { fireOneShot(it, gain = lastVolume) }

        idle?.release()
        rev?.release()
        idle = null
        rev = null
        synchronized(oneShots) {
            oneShots.forEach { it.release() }
            oneShots.clear()
        }
        profile = null
        lastIdleVol = -1f
        lastRevVol = -1f
        lastSpeed = -1f
        smoothedIdleVol = 0f
        smoothedRevVol = 0f
    }

    /**
     * Called from [EngineSoundEngine.emit] every telemetry tick.
     * Cross-fades idle ↔ rev by [rpmNorm] (0..1) and scales overall gain by [volume].
     */
    @SuppressLint("NewApi")
    fun update(rpmNorm: Float, volume: Float) {
        lastRpmNorm = rpmNorm.coerceIn(0f, 1f)
        lastVolume = volume.coerceIn(0f, 1f)

        // Equal-power crossfade: idle dominates at low RPM, rev at high RPM.
        // At rpm=0.5 both contribute ~0.71×, summing close to 1 in perceived loudness.
        val curve = lastRpmNorm
        val idleGain = (1f - curve).let { kotlin.math.sqrt(it) } * lastVolume
        val revGain  = curve.let { kotlin.math.sqrt(it) } * lastVolume
        // If we don't have a rev loop, idle alone handles everything.
        val targetIdle = if (rev == null) lastVolume else idleGain
        val targetRev  = if (rev == null) 0f else revGain

        // Move smoothed values toward target. This gives engine-start fade-in
        // (starts at 0, climbs over ~5-8 ticks ≈ ~300 ms) and naturally smooths
        // every rapid rpm-driven crossfade so we never write a clicky jump to
        // MediaPlayer.setVolume.
        smoothedIdleVol += (targetIdle - smoothedIdleVol) * volSmoothingAlpha
        smoothedRevVol  += (targetRev  - smoothedRevVol)  * volSmoothingAlpha

        if (abs(smoothedIdleVol - lastIdleVol) > 0.005f) {
            idle?.setVolume(smoothedIdleVol); lastIdleVol = smoothedIdleVol
        }
        if (abs(smoothedRevVol - lastRevVol) > 0.005f) {
            rev?.setVolume(smoothedRevVol); lastRevVol = smoothedRevVol
        }

        // Mild speed modulation within each loop so the same 1.5 s clip doesn't sound
        // perfectly static across a 0-50 km/h sweep. Range chosen narrow so we don't
        // ruin the timbre of the underlying recording.
        val speed = 0.9f + 0.20f * lastRpmNorm   // 0.90 .. 1.10
        if (abs(speed - lastSpeed) > 0.02f) {
            try {
                idle?.setSpeed(speed)
                rev?.setSpeed(speed)
                lastSpeed = speed
            } catch (e: Throwable) {
                Log.w(TAG, "setSpeed failed", e)
            }
        }
    }

    /** Called by [EngineSoundEngine] when it sees a sharp throttle drop. */
    fun fireDecel() {
        profile?.sampleSections?.get("decel")?.let { fireOneShot(it, gain = lastVolume) }
    }

    private fun fireOneShot(section: SampleSection, gain: Float) {
        val sp = SectionPlayer(context, section, looping = false)
        if (!sp.prepare()) { sp.release(); return }
        sp.setVolume(gain.coerceIn(0f, 1f))
        sp.play()
        synchronized(oneShots) { oneShots.add(sp) }
        // Auto-cleanup after the section's natural duration plus 50 ms grace.
        mainHandler.postDelayed({
            synchronized(oneShots) { oneShots.remove(sp) }
            sp.release()
        }, section.durationMs + 50L)
    }

    companion object {
        private const val TAG = "CompositionEnginePlayer"
    }
}

/**
 * Plays a [SampleSection] from a res/raw resource. Loops the window between
 * [SampleSection.startMs] and [SampleSection.endMs] when [looping] is true, or
 * plays once and stops at endMs otherwise.
 *
 * Section looping is approximate: a 50 ms position poll seeks back to startMs
 * when the player crosses endMs, leaving a small audible seam. Acceptable for v1.
 */
private class SectionPlayer(
    private val context: Context,
    private val section: SampleSection,
    private val looping: Boolean,
) {
    private val mp = MediaPlayer()
    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    @Volatile private var released = false

    fun prepare(): Boolean {
        val resId = context.resources.getIdentifier(section.rawAsset, "raw", context.packageName)
        if (resId == 0) {
            Log.w("SectionPlayer", "raw/${section.rawAsset} not found")
            return false
        }
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            val afd = context.resources.openRawResourceFd(resId) ?: return false
            afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setVolume(0f, 0f)
            mp.prepare()
            mp.seekTo(section.startMs)
            true
        } catch (e: Throwable) {
            Log.e("SectionPlayer", "prepare failed for ${section.rawAsset}", e)
            false
        }
    }

    fun play() {
        if (released) return
        try { mp.start() } catch (e: Throwable) { Log.w("SectionPlayer", "start failed", e); return }
        // Poll position every 50 ms to enforce the section endMs boundary.
        val poll = object : Runnable {
            override fun run() {
                if (released) return
                try {
                    if (mp.isPlaying && mp.currentPosition >= section.endMs) {
                        if (looping) {
                            mp.seekTo(section.startMs)
                        } else {
                            mp.pause()
                            return
                        }
                    }
                } catch (_: Throwable) { /* released between checks */ }
                handler.postDelayed(this, 50L)
            }
        }
        pollRunnable = poll
        handler.postDelayed(poll, 50L)
    }

    fun setVolume(v: Float) {
        if (released) return
        try { mp.setVolume(v, v) } catch (_: Throwable) {}
    }

    @SuppressLint("NewApi")
    fun setSpeed(s: Float) {
        if (released) return
        mp.playbackParams = (mp.playbackParams ?: PlaybackParams()).setSpeed(s)
    }

    fun release() {
        released = true
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        try { mp.stop() } catch (_: Throwable) {}
        try { mp.release() } catch (_: Throwable) {}
    }
}
