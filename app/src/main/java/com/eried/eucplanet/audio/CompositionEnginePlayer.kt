package com.eried.eucplanet.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlin.math.abs

/**
 * Multi-section playback for sampled engines. Procedural [EngineSynth] plays a
 * single buffer it owns; this player composes the engine sound from named
 * sections inside one or more raw resources (picked in deletelater.html).
 *
 * Section types:
 *  - "idle_loop", sustained low-RPM loop (required)
 *  - "rev_loop" , sustained high-RPM loop (optional, without it, idle pitch-shifts up)
 *  - "startup", one-shot transient played on engine start (optional)
 *  - "decel": one-shot transient played when throttle closes sharply (optional)
 *  - "shutdown" , one-shot transient played on engine stop (optional)
 *
 * Looping sections use [ExoPlayer] with [ClippingMediaSource] + REPEAT_MODE_ONE so
 * the decoder seamlessly stitches the startMs..endMs window back to its start , 
 * no audible click at the seam. The two loops (idle, rev) play simultaneously and
 * are crossfaded by RPM. One-shots are spawned on demand for startup/decel/shutdown.
 */
@OptIn(UnstableApi::class)
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
    // Smoothed actual volumes, drives engine-start fade-in plus the idle ↔ rev
    // crossfade. Alpha 0.15 ≈ ~85 ms time constant at ~100 Hz telemetry.
    @Volatile private var smoothedIdleVol: Float = 0f
    @Volatile private var smoothedRevVol: Float = 0f
    private val volSmoothingAlpha = 0.15f

    fun isPlaying(): Boolean = idle != null

    fun start(profile: EngineProfile) {
        if (this.profile?.key == profile.key && idle != null) return
        stop()
        val sections = profile.sampleSections ?: return
        this.profile = profile

        sections["idle_loop"]?.let { sec ->
            idle = SectionPlayer(context, sec, looping = true).also {
                if (!it.prepare()) { it.release(); idle = null; Log.w(TAG, "idle_loop failed") }
                else { it.setVolume(0f); it.play() }
            }
        }
        sections["rev_loop"]?.let { sec ->
            rev = SectionPlayer(context, sec, looping = true).also {
                if (!it.prepare()) { it.release(); rev = null; Log.w(TAG, "rev_loop failed") }
                else { it.setVolume(0f); it.play() }
            }
        }
        sections["startup"]?.let { fireOneShot(it, gain = 1f) }
    }

    fun stop() {
        val shutdown = profile?.sampleSections?.get("shutdown")
        val fadeDurMs = shutdown?.durationMs?.coerceIn(200, 2500) ?: 300
        shutdown?.let { fireOneShot(it, gain = lastVolume) }

        val idleSnap = idle
        val revSnap = rev
        val startIdle = smoothedIdleVol
        val startRev = smoothedRevVol
        idle = null
        rev = null
        profile = null
        val steps = 24
        val stepMs = (fadeDurMs / steps).coerceAtLeast(8)
        for (i in 1..steps) {
            val gain = 1f - (i.toFloat() / steps)
            mainHandler.postDelayed({
                idleSnap?.setVolume(startIdle * gain)
                revSnap?.setVolume(startRev * gain)
            }, (i * stepMs).toLong())
        }
        mainHandler.postDelayed({
            idleSnap?.release()
            revSnap?.release()
            synchronized(oneShots) {
                oneShots.forEach { it.release() }
                oneShots.clear()
            }
        }, (fadeDurMs + 100).toLong())

        lastIdleVol = -1f
        lastRevVol = -1f
        lastSpeed = -1f
        smoothedIdleVol = 0f
        smoothedRevVol = 0f
    }

    fun update(rpmNorm: Float, volume: Float) {
        lastRpmNorm = rpmNorm.coerceIn(0f, 1f)
        lastVolume = volume.coerceIn(0f, 1f)

        // Equal-power crossfade between idle and rev. At rpm=0.5 each contributes
        // sqrt(0.5)≈0.71, summing close to 1 in perceived loudness.
        val targetIdle = if (rev == null) lastVolume else kotlin.math.sqrt(1f - lastRpmNorm) * lastVolume
        val targetRev  = if (rev == null) 0f else kotlin.math.sqrt(lastRpmNorm) * lastVolume

        smoothedIdleVol += (targetIdle - smoothedIdleVol) * volSmoothingAlpha
        smoothedRevVol  += (targetRev  - smoothedRevVol)  * volSmoothingAlpha

        if (abs(smoothedIdleVol - lastIdleVol) > 0.005f) {
            idle?.setVolume(smoothedIdleVol); lastIdleVol = smoothedIdleVol
        }
        if (abs(smoothedRevVol - lastRevVol) > 0.005f) {
            rev?.setVolume(smoothedRevVol); lastRevVol = smoothedRevVol
        }

        // Built-in sampled profiles keep playback speed at 1.0x (the idle/rev
        // crossfade conveys speed; ExoPlayer's time-stretch painted artifacts).
        // Single-file custom profiles (pitchModulated, no rev_loop) are the one
        // exception and pitch the idle clip by RPM, handled just below.

        // Single-file custom mode: no rev loop, so convey speed by pitching idle.
        // Built-in sampled profiles set pitchModulated=false and skip this.
        if (profile?.pitchModulated == true && rev == null) {
            val curved = lastRpmNorm.let { it * (0.4f + 0.6f * it) }
            val targetSpeed = 0.6f + 1.2f * curved   // 0.6x..1.8x, matches SampledEnginePlayer
            if (kotlin.math.abs(targetSpeed - lastSpeed) > 0.01f) {
                idle?.setSpeed(targetSpeed)
                lastSpeed = targetSpeed
            }
        }
    }

    fun fireDecel() {
        profile?.sampleSections?.get("decel")?.let { fireOneShot(it, gain = lastVolume) }
    }

    private fun fireOneShot(section: SampleSection, gain: Float) {
        val sp = SectionPlayer(context, section, looping = false)
        val cleanup = Runnable {
            synchronized(oneShots) { oneShots.remove(sp) }
            sp.release()
        }
        // Custom URIs have unknown length: release on natural end (STATE_ENDED),
        // with a 15s backstop if a decode error means it never fires. Raw one-shots
        // keep the duration timer (their length is known from startMs/endMs).
        if (section.isCustom) sp.setOnEnded { mainHandler.post(cleanup) }
        if (!sp.prepare()) { sp.release(); return }
        sp.setVolume(gain.coerceIn(0f, 1f))
        sp.play()
        synchronized(oneShots) { oneShots.add(sp) }
        mainHandler.postDelayed(cleanup, if (section.isCustom) 15_000L else section.durationMs + 200L)
    }

    companion object {
        private const val TAG = "CompositionEnginePlayer"
    }
}

/**
 * Plays a [SampleSection] from a res/raw resource via [ExoPlayer]. When
 * [looping] is true, ExoPlayer's [ClippingMediaSource] reports a duration
 * matching the section window and REPEAT_MODE_ONE handles a gapless restart , 
 * no perceptible seam.
 */
@OptIn(UnstableApi::class)
private class SectionPlayer(
    private val context: Context,
    private val section: SampleSection,
    private val looping: Boolean,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val player: ExoPlayer = ExoPlayer.Builder(context)
        // Bind to the main looper so callers from any thread can talk to us safely
        // through [postToMain], all actual mutations happen there.
        .setLooper(Looper.getMainLooper())
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ false
        )
        .build()
    @Volatile private var released = false
    @Volatile private var onEnded: (() -> Unit)? = null
    fun setOnEnded(cb: () -> Unit) { onEnded = cb }

    private inline fun postToMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    fun prepare(): Boolean {
        val mediaSource = if (section.isCustom) {
            val factory = DataSource.Factory { DefaultDataSource.Factory(context).createDataSource() }
            ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(section.uri)))
        } else {
            val resId = context.resources.getIdentifier(section.rawAsset, "raw", context.packageName)
            if (resId == 0) {
                Log.w("SectionPlayer", "raw/${section.rawAsset} not found")
                return false
            }
            val uri = RawResourceDataSource.buildRawResourceUri(resId)
            val factory = DataSource.Factory { RawResourceDataSource(context) }
            ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri))
        }
        return try {
            postToMain {
                if (released) return@postToMain
                // Raw sections clip to [startMs..endMs]; custom URIs play the whole file.
                val source = if (section.isCustom) mediaSource
                    else ClippingMediaSource(mediaSource, section.startMs * 1000L, section.endMs * 1000L)
                player.setMediaSource(source)
                player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                if (!looping) {
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) onEnded?.invoke()
                        }
                    })
                }
                player.volume = 0f
                player.prepare()
            }
            true
        } catch (e: Throwable) {
            Log.e("SectionPlayer", "prepare failed for ${if (section.isCustom) section.uri else section.rawAsset}", e)
            false
        }
    }

    fun play() = postToMain { if (!released) player.play() }

    fun setVolume(v: Float) = postToMain {
        if (released) return@postToMain
        try { player.volume = v.coerceIn(0f, 1f) } catch (_: Throwable) {}
    }

    fun setSpeed(s: Float) = postToMain {
        if (released) return@postToMain
        try { player.setPlaybackSpeed(s.coerceIn(0.5f, 2.0f)) } catch (_: Throwable) {}
    }

    fun release() {
        released = true
        postToMain {
            try { player.stop() } catch (_: Throwable) {}
            try { player.release() } catch (_: Throwable) {}
        }
    }
}
