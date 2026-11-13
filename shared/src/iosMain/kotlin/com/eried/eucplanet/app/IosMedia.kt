package com.eried.eucplanet.app

import com.eried.eucplanet.flic.MediaControls
import com.eried.eucplanet.platform.Log
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.otherAudioPlaying
import platform.Foundation.NSLock

/**
 * The music actions on iOS. No app can send another app's player a key, but
 * holding the audio session without mixing makes the playing app pause, and
 * letting it go with NotifyOthersOnDeactivation tells it to resume, as Music
 * and Spotify do. So Pause and Play/Pause work; Start music, Next and Previous
 * cannot, and the pickers leave them out (SettingsHost.drivesMusicPlayer).
 *
 * The hold is one of [IosAudioSession]'s, so a voice line or tone while the
 * music is paused does not bring it back when it ends; only Play/Pause does.
 */
internal object IosMedia : MediaControls {
    private const val TAG = "Actions"
    private val lock = NSLock()
    private var holding = false

    private fun otherPlaying(): Boolean = AVAudioSession.sharedInstance().otherAudioPlaying

    override fun pause() = locked {
        if (otherPlaying()) hold() else Log.i(TAG, "pause: nothing playing")
    }

    override fun playPauseOrStart() = locked {
        when {
            // Also when the rider started it again by hand while paused here:
            // that app took the session back, so take it once more.
            otherPlaying() -> hold()
            holding -> {
                holding = false
                IosAudioSession.release(IosAudioSession.Others.PAUSE)
                Log.i(TAG, "music let go to resume")
            }
            else -> Log.i(TAG, "play: no music to resume, and iOS cannot start another app's")
        }
    }

    override fun startMusic() { Log.i(TAG, "start music not on iOS") }
    override fun next() { Log.i(TAG, "next track not on iOS") }
    override fun previous() { Log.i(TAG, "previous track not on iOS") }

    private fun hold() {
        if (!holding) {
            holding = true
            IosAudioSession.acquire(IosAudioSession.Others.PAUSE)
        }
        // Activate again even when already held: another app that took the
        // session back has to be interrupted anew.
        IosAudioSession.reassert()
        Log.i(TAG, "music paused")
    }

    private inline fun locked(block: () -> Unit) {
        lock.lock()
        try { block() } finally { lock.unlock() }
    }
}
