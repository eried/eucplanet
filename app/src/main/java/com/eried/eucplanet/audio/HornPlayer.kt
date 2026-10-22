package com.eried.eucplanet.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rider's own horn sound, played by the phone.
 *
 * The file is copied into app storage when picked, so it survives the source
 * being moved or the grant being lost, and it is decoded into a SoundPool
 * ahead of time: a horn that waits on a decoder is a horn that sounds late.
 * While it plays, other audio (music, podcasts) is ducked through transient
 * audio focus and comes back when the horn ends. Alarm tones take no focus,
 * so they keep sounding over it.
 */
@Singleton
class HornPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "HornPlayer"
        /** Longer than this is a song, not a horn. */
        const val MAX_DURATION_MS = 5_000L
        /** SoundPool decodes into memory; keep the file small. */
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private const val FILE_NAME = "horn_sound"
    }

    enum class ImportResult { OK, TOO_LONG, TOO_BIG, UNREADABLE }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var soundId = 0
    @Volatile private var ready = false
    @Volatile private var durationMs = 0L
    private var focus: AudioFocusRequest? = null

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (id == soundId) ready = status == 0
        }
        if (file().exists()) load()
    }

    private fun file() = File(File(context.filesDir, "horn"), FILE_NAME)

    /** True once a sound is decoded and can play at once. */
    val isReady: Boolean get() = ready

    /** Copy [uri] in as the horn sound after checking it is a short, readable clip. */
    fun import(uri: Uri): ImportResult {
        val tmp = File(context.cacheDir, "horn_import")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val copied = input.copyTo(out)
                    if (copied > MAX_FILE_BYTES) return ImportResult.TOO_BIG
                }
            } ?: return ImportResult.UNREADABLE
            val duration = durationOf(tmp) ?: return ImportResult.UNREADABLE
            if (duration > MAX_DURATION_MS) return ImportResult.TOO_LONG
            val dest = file()
            dest.parentFile?.mkdirs()
            tmp.copyTo(dest, overwrite = true)
            load()
            return ImportResult.OK
        } catch (e: Exception) {
            Log.w(TAG, "import failed", e)
            return ImportResult.UNREADABLE
        } finally {
            tmp.delete()
        }
    }

    private fun durationOf(f: File): Long? = try {
        MediaMetadataRetriever().run {
            try {
                setDataSource(f.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.takeIf { it > 0 }
            } finally { release() }
        }
    } catch (_: Exception) { null }

    private fun load() {
        val f = file()
        ready = false
        if (soundId != 0) pool.unload(soundId)
        soundId = 0
        if (!f.exists()) return
        durationMs = durationOf(f) ?: 0L
        soundId = pool.load(f.absolutePath, 1)
    }

    /**
     * Play the horn sound once. Returns false when nothing is ready to play,
     * so the caller can fall back to the wheel's own horn.
     */
    fun play(): Boolean {
        if (!ready || soundId == 0) return false
        requestFocus()
        val stream = pool.play(soundId, 1f, 1f, 1, 0, 1f)
        if (stream == 0) { abandonFocus(); return false }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ abandonFocus() }, (durationMs.takeIf { it > 0 } ?: 1_000L) + 150L)
        return true
    }

    private fun requestFocus() {
        abandonFocus()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        focus = req
        runCatching { audioManager.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focus = null
    }

    /** Headphones, a Bluetooth speaker or USB audio is the current output. */
    fun externalOutputActive(): Boolean = AudioOutput.isExternalActive(audioManager)
}
