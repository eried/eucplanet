package com.eried.eucplanet.flic

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.eried.eucplanet.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The volume buttons with the screen off: "Also when the screen is off" in
 * the Volume keys section.
 *
 * An app only gets raw volume key events through its visible window, so the
 * activity's handler goes deaf the moment the screen locks or another app
 * comes in front. The one route Android leaves is a media session that owns
 * a "remote" volume: while it is the most recent session that reports
 * playing, every volume press is routed to its [VolumeProvider] instead of
 * the phone volume, screen off included. That is also the price, written in
 * the warning the rider accepts to turn this on: the buttons stop changing
 * the phone volume.
 *
 * "Most recent" is the catch. A music app that starts, or only moves to its
 * next song, becomes the more recent session and the buttons go back to
 * changing its volume (seen on a Pixel: actions until the song changed, then
 * volume, then actions again once the app was opened). So the session
 * listens for any change in what is playing on the phone and claims the
 * buttons again, by stepping to paused and back to playing, which is the
 * transition Android ranks sessions on. Media buttons are not affected: those
 * follow the app that is really producing sound.
 *
 * Nothing is played. The session only claims the keys; [VolumeKeyPresses]
 * reads the adjust calls back into clicks and holds, and the bound action
 * goes through [FlicManager] like a Flic press. While the app is on screen
 * the activity still consumes the keys first, so a press never fires twice.
 *
 * Lives with [com.eried.eucplanet.service.WheelService]: started when the
 * option is on, stopped when it goes off or the service ends.
 */
class VolumeKeySession(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val flicManager: FlicManager,
) {
    companion object {
        private const val TAG = "VolumeKeySession"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val presses = VolumeKeyPresses()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: MediaSession? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val reclaim = Runnable {
        val s = session ?: return@Runnable
        s.setPlaybackState(state(PlaybackState.STATE_PAUSED))
        s.setPlaybackState(state(PlaybackState.STATE_PLAYING))
    }

    /** Fires when any app starts, stops or changes what it plays. Says nothing about which app. */
    private val playbackWatcher = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            // Twice: Android reorders the sessions a moment after the audio
            // changes, and a claim made before that is undone by it.
            handler.removeCallbacks(reclaim)
            handler.postDelayed(reclaim, 300L)
            handler.postDelayed(reclaim, 1500L)
        }
    }

    private fun state(state: Int) = PlaybackState.Builder()
        .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
        .build()

    private val expire = object : Runnable {
        override fun run() {
            fire(presses.expire(SystemClock.uptimeMillis()))
            schedule()
        }
    }

    fun start() {
        if (session != null) return
        val s = try {
            MediaSession(context, "EUC Planet volume keys")
        } catch (e: Exception) {
            Log.w(TAG, "No media session for the volume keys", e)
            return
        }
        s.setCallback(object : MediaSession.Callback() {}, handler)
        s.setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                val now = SystemClock.uptimeMillis()
                presses.adjust(direction, now).forEach(::fire)
                schedule()
            }
        })
        // "Playing" is what makes it the session the keys go to. No audio is involved.
        s.setPlaybackState(state(PlaybackState.STATE_PLAYING))
        s.isActive = true
        session = s
        runCatching { audioManager.registerAudioPlaybackCallback(playbackWatcher, handler) }
        Log.i(TAG, "Volume keys claimed for the screen-off bindings")
    }

    fun stop() {
        handler.removeCallbacks(expire)
        handler.removeCallbacks(reclaim)
        runCatching { audioManager.unregisterAudioPlaybackCallback(playbackWatcher) }
        session?.let {
            it.isActive = false
            it.release()
            Log.i(TAG, "Volume keys given back to the phone")
        }
        session = null
        scope.cancel()
    }

    private fun schedule() {
        handler.removeCallbacks(expire)
        presses.expiresAt?.let { handler.postAtTime(expire, it + 1) }
    }

    private fun fire(fired: VolumeKeyPresses.Fired?) {
        fired ?: return
        scope.launch {
            val s = settingsRepository.get()
            if (!s.volumeKeysEnabled || !s.volumeKeysScreenOff) return@launch
            val action = when (fired.key) {
                VolumeKeyPresses.Key.UP -> if (fired.hold) s.volumeUpHold else s.volumeUpClick
                VolumeKeyPresses.Key.DOWN -> if (fired.hold) s.volumeDownHold else s.volumeDownClick
            }
            Log.d(TAG, "${fired.key} ${if (fired.hold) "hold" else "click"} -> $action")
            flicManager.dispatchActionByName(action)
        }
    }
}
