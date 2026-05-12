package com.eried.eucplanet.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot SFX overlay for the sampled-engine path. The procedural [EngineSynth]
 * already renders pops, decel grit and engine-brake whine into its own PCM buffer,
 * but the sampled path goes through [SampledEnginePlayer]'s MediaPlayer where we
 * have no mix point. So we use Android's SoundPool to layer short SFX *on top of*
 * MediaPlayer's stream at the system level.
 *
 * Wired by [EngineSoundEngine]:
 *  - [load] when a sampled profile becomes active — preloads its pop and brake
 *    whine assets if the profile declares them.
 *  - [firePop] when [EngineSoundEngine.emit] dequeues a pending pop.
 *  - [unload] / [release] on profile switch / engine stop.
 *
 * Cheap: SoundPool keeps decoded PCM in memory, so triggering a pop is allocation-free
 * and runs on the audio thread Android picks. We cap at 4 simultaneous streams since
 * pops should overlap but not stack endlessly.
 */
@Singleton
class SfxSidecar @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Volatile private var pool: SoundPool? = null
    /** asset basename → SoundPool sound ID (after load completes). */
    private val loaded = HashMap<String, Int>()
    /** asset basename → true once the SoundPool callback fires (only then is play() valid). */
    private val ready = HashSet<String>()

    private var rngState = 0x5A6B7C8D

    private fun nextRand01(): Float {
        var x = rngState
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        rngState = x
        return (x and 0x7FFFFFFF) / Int.MAX_VALUE.toFloat()
    }

    /** Lazily create the SoundPool. Safe to call repeatedly. */
    private fun ensurePool(): SoundPool {
        pool?.let { return it }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val sp = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(attrs)
            .build()
        sp.setOnLoadCompleteListener { _, soundId, status ->
            if (status == 0) {
                synchronized(this) {
                    loaded.entries.firstOrNull { it.value == soundId }?.let { ready.add(it.key) }
                }
            } else {
                Log.w(TAG, "SoundPool load failed for soundId=$soundId, status=$status")
            }
        }
        pool = sp
        return sp
    }

    /**
     * Preload the SFX assets declared by [profile] so they're ready when a pop fires.
     * No-op for assets already loaded; SoundPool dedupes by resource ID internally,
     * but we dedupe at our level to avoid duplicate load() calls during settings churn.
     */
    fun load(profile: EngineProfile) {
        val sp = ensurePool()
        profile.popSampleAsset?.let { name ->
            if (loaded.containsKey(name)) return@let
            val resId = context.resources.getIdentifier(name, "raw", context.packageName)
            if (resId == 0) {
                Log.w(TAG, "SFX asset raw/$name not found")
                return@let
            }
            val id = sp.load(context, resId, 1)
            synchronized(this) { loaded[name] = id }
        }
        profile.brakeWhineSampleAsset?.let { name ->
            if (loaded.containsKey(name)) return@let
            val resId = context.resources.getIdentifier(name, "raw", context.packageName)
            if (resId == 0) {
                Log.w(TAG, "SFX asset raw/$name not found")
                return@let
            }
            val id = sp.load(context, resId, 1)
            synchronized(this) { loaded[name] = id }
        }
    }

    /**
     * Trigger one playback of [assetName] with master gain [volume] (0..1).
     * Randomizes pitch slightly so consecutive pops don't sound identical.
     * No-op if the asset isn't loaded yet — pops are non-critical, dropping the
     * first one or two during cold-start is acceptable.
     */
    fun firePop(assetName: String?, volume: Float) {
        if (assetName == null) return
        val sp = pool ?: return
        val soundId = synchronized(this) {
            if (assetName !in ready) return
            loaded[assetName] ?: return
        }
        val v = volume.coerceIn(0f, 1f)
        // Pitch jitter 0.92..1.08 so the same sample doesn't sound robotic when repeated.
        val rate = 0.92f + 0.16f * nextRand01()
        sp.play(soundId, v, v, /* priority */ 1, /* loop */ 0, rate)
    }

    /** Unload all assets and free the SoundPool. Called on engine stop / profile switch. */
    fun release() {
        val sp = pool ?: return
        synchronized(this) {
            loaded.clear()
            ready.clear()
        }
        try { sp.release() } catch (_: Throwable) {}
        pool = null
    }

    companion object {
        private const val TAG = "SfxSidecar"
        private const val MAX_STREAMS = 4
    }
}
