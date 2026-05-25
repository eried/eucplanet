package com.eried.eucplanet.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import kotlin.math.abs

/**
 * Plays a recorded engine sample looped, varying playback speed by RPM so it
 * sounds like the engine is revving. Used for the "Sampled …" presets, the
 * procedural [EngineSynth] is bypassed entirely when this is active.
 *
 * Lifecycle: [start] / [stop] are idempotent and cheap; one MediaPlayer is
 * created per start. [update] is called from the same telemetry tick as the
 * procedural path so the two paths share rev / load smoothing.
 */
class SampledEnginePlayer(private val context: Context) {

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var currentProfileKey: String? = null
    @Volatile private var lastSpeed: Float = 1f
    @Volatile private var lastVolume: Float = 0f

    fun isPlaying(): Boolean = player != null

    /** Starts looped playback of the sample baked into [profile]. No-op if the same profile is already playing. */
    fun start(profile: EngineProfile) {
        val base = profile.sampleAssetBase ?: return
        if (currentProfileKey == profile.key && player != null) return
        stop()
        val resName = "${base}"
        val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
        if (resId == 0) {
            Log.w(TAG, "Sample resource raw/$resName not found for ${profile.key}")
            return
        }
        try {
            val mp = MediaPlayer()
            // setAudioAttributes must be called before prepare(), that's why we don't use the convenience
            // MediaPlayer.create(context, resId), which prepares internally.
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            val afd = context.resources.openRawResourceFd(resId) ?: run {
                Log.w(TAG, "openRawResourceFd returned null for raw/$resName")
                return
            }
            afd.use {
                mp.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.prepare()
            // Don't start playback yet, let the first update() set the speed/volume so we don't blast at default speed.
            currentProfileKey = profile.key
            player = mp
            Log.i(TAG, "Loaded sample raw/$resName for profile ${profile.key}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load sample raw/$resName", e)
        }
    }

    /** Stops playback and releases the player. Idempotent. */
    fun stop() {
        val p = player
        player = null
        currentProfileKey = null
        try {
            p?.stop()
        } catch (_: Throwable) {}
        try {
            p?.release()
        } catch (_: Throwable) {}
    }

    /**
     * Update playback parameters from telemetry-derived RPM and target volume.
     *
     * @param rpmNorm 0..1 fraction of the engine's RPM band (idle..max)
     * @param volume  0..1 master volume
     */
    @SuppressLint("NewApi") // PlaybackParams is API 23+, our minSdk is at least 24
    fun update(rpmNorm: Float, volume: Float) {
        val mp = player ?: return
        val vol = volume.coerceIn(0f, 1f)
        // When the idle envelope fades us all the way out (parked + FADE elapsed) keep the
        // MediaPlayer paused instead of looping silently. Resume on the first non-silent tick.
        if (vol < MUTE_THRESHOLD) {
            if (mp.isPlaying) {
                try { mp.pause() } catch (_: Throwable) {}
            }
            lastVolume = 0f
            return
        }

        // Map rpmNorm -> playback speed. At idle (rpmNorm=0) we want ~0.6x for a deeper rumble,
        // at full revs (rpmNorm=1) we want ~1.8x for the high-rev wail. A slight curve makes it feel less linear.
        val curved = rpmNorm.coerceIn(0f, 1f).let { it * (0.4f + 0.6f * it) }
        val targetSpeed = 0.6f + 1.2f * curved        // 0.6..1.8
        if (abs(targetSpeed - lastSpeed) > 0.01f || !mp.isPlaying) {
            try {
                if (!mp.isPlaying) mp.start()
                mp.playbackParams = PlaybackParams().setSpeed(targetSpeed)
                lastSpeed = targetSpeed
            } catch (e: Throwable) {
                Log.w(TAG, "setPlaybackParams failed", e)
            }
        }
        if (abs(vol - lastVolume) > 0.01f) {
            try {
                mp.setVolume(vol, vol)
                lastVolume = vol
            } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "SampledEnginePlayer"
        // Below this gain the listener can't hear anything anyway, pause the player
        // to save the decode loop. Hysteresis-free: resume kicks in the moment the
        // engine asks for non-trivial volume again.
        private const val MUTE_THRESHOLD = 0.01f
    }
}
