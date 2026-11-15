package com.eried.eucplanet.app

import com.eried.eucplanet.platform.Log
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryOptions
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBuiltInReceiver
import platform.AVFAudio.AVAudioSessionPortBuiltInSpeaker
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.currentRoute
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSLock

/**
 * The one owner of the app's AVAudioSession, the counterpart of Android's
 * audio focus. The voice and the tones each hold it while they sound, with
 * what they want from other apps' audio; the session is active while anyone
 * holds it and is let go, telling the others, when the last one is done, so
 * the rider's music comes back up or resumes.
 *
 * The session is shared by everything in the app, so while several hold it
 * the strongest wish wins (pause over duck over leave alone).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosAudioSession {
    private const val TAG = "AudioSession"

    /** What a holder does to other apps' audio, weakest first. */
    enum class Others {
        /** Plays over it, as a beep on Android, which asks for no focus. */
        LEAVE,

        /** Lowers it while sounding ("DUCK", Android's transient may-duck focus). */
        DUCK,

        /** Interrupts it so it pauses and resumes after ("PAUSE", Android's transient focus). */
        PAUSE;

        companion object {
            /** The voice's "While speaking" setting (voiceAudioFocus). */
            fun ofVoiceFocus(value: String): Others = when (value) {
                "PAUSE" -> PAUSE
                "OFF" -> LEAVE
                else -> DUCK
            }
        }
    }

    private val lock = NSLock()
    private val holders = IntArray(Others.entries.size)
    private var applied: Pair<Others, Boolean>? = null

    /** Holders recording the microphone (the studio's take): the session plays and records. */
    private var recorders = 0

    /** The microphone records while held; pair with one [releaseRecording]. */
    fun acquireRecording() = locked {
        recorders++
        apply()
    }

    fun releaseRecording() = locked {
        if (recorders == 0) return@locked
        recorders--
        if (idle()) deactivate() else apply()
    }

    private fun idle() = recorders == 0 && holders.all { it == 0 }

    private fun deactivate() {
        applied = null
        val ok = call("deactivate") { err ->
            AVAudioSession.sharedInstance().setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, err)
        }
        if (ok) Log.i(TAG, "released")
    }

    /** Takes a hold that wants [others]; pair every call with one [release] of the same. */
    fun acquire(others: Others) = locked {
        holders[others.ordinal]++
        apply()
    }

    /** Drops one hold of [others]; the last one out lets the session go. */
    fun release(others: Others) = locked {
        if (holders[others.ordinal] == 0) return@locked
        holders[others.ordinal]--
        if (idle()) deactivate() else apply()
    }

    /** Headphones, Bluetooth or CarPlay audio, not the phone's own speaker. */
    fun externalOutputActive(): Boolean =
        AVAudioSession.sharedInstance().currentRoute.outputs.any {
            val type = (it as AVAudioSessionPortDescription).portType
            type != AVAudioSessionPortBuiltInSpeaker && type != AVAudioSessionPortBuiltInReceiver
        }

    /**
     * Activates the session again with what the holders want, as after an
     * interruption (a call) took it away while still held.
     */
    fun reassert() = locked {
        if (!idle()) { applied = null; apply() }
    }

    private fun apply() {
        // A recorder alone leaves the others alone.
        val want = Others.entries.lastOrNull { holders[it.ordinal] > 0 } ?: Others.LEAVE
        val recording = recorders > 0
        if (want to recording == applied) return
        val base: AVAudioSessionCategoryOptions = when (want) {
            Others.LEAVE -> AVAudioSessionCategoryOptionMixWithOthers
            Others.DUCK -> AVAudioSessionCategoryOptionMixWithOthers or AVAudioSessionCategoryOptionDuckOthers
            // No mix option: activating interrupts the others, and letting go
            // with the notify option tells them to resume.
            Others.PAUSE -> 0uL
        }
        // Recording keeps the sound on the speaker, not the earpiece, and takes a Bluetooth headset's microphone.
        val options = if (recording) base or AVAudioSessionCategoryOptionDefaultToSpeaker or
            AVAudioSessionCategoryOptionAllowBluetoothHFP else base
        val category = if (recording) AVAudioSessionCategoryPlayAndRecord else AVAudioSessionCategoryPlayback
        val session = AVAudioSession.sharedInstance()
        val ok = call("category") { err -> session.setCategory(category, options, err) } &&
            call("activate") { err -> session.setActive(true, err) }
        if (ok) {
            applied = want to recording
            Log.i(TAG, "active, others: $want, recording: $recording")
        }
    }

    private inline fun call(what: String, block: (kotlinx.cinterop.CPointer<ObjCObjectVar<NSError?>>) -> Boolean): Boolean =
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            val ok = block(err.ptr)
            if (!ok) Log.w(TAG, "$what failed: ${err.value?.localizedDescription}")
            ok
        }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
