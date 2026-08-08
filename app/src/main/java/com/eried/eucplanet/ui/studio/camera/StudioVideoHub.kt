package com.eried.eucplanet.ui.studio.camera

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

/**
 * Replay-video analog of [StudioCameraHub], scoped to OFFLINE export: it decodes
 * frame-accurate stills out of previously recorded clips so the offscreen export
 * renderer can composite a replay-video face under the overlay. Each clip is
 * referenced by a persistable SAF URI string (phone-only; there is no
 * HUD/Wear/Garmin counterpart) and backed by one [MediaMetadataRetriever] held
 * open for the lifetime of the binding.
 *
 * The interactive on-screen preview does NOT use this hub - it plays through a
 * real [com.eried.eucplanet.ui.studio.ReplayVideoPlayer] (ExoPlayer), because the
 * retriever is a thumbnail/seek API and far too slow for smooth scrubbing. The
 * hub is still bound while replaying so [durationMs] can size the clip window and
 * so export can pull exact frames offline. The last decoded export frame per uri
 * is cached so re-rendering the same position skips a re-decode.
 */
class StudioVideoHub(private val context: Context) {
    private val retrievers = mutableMapOf<String, MediaMetadataRetriever>()
    private val durations = mutableMapOf<String, Long>()
    private val invalid = mutableSetOf<String>()
    private val lastFrame = mutableMapOf<String, Pair<Long, ImageBitmap>>()

    /** True once [uri] has failed to open or decode; it will not be retried until re-bound. */
    fun isInvalid(uri: String): Boolean = uri in invalid

    /** Clip length in milliseconds, or 0L if [uri] is not open or invalid. */
    fun durationMs(uri: String): Long = durations[uri] ?: 0L

    /**
     * The frame-accurate ([MediaMetadataRetriever.OPTION_CLOSEST]) still at
     * [timeUs] (microseconds), or null when the uri is invalid, not bound, or the
     * frame could not be decoded. Used by the offline export path (blocking is
     * acceptable there); the last frame per uri is cached at ~15fps granularity so
     * re-rendering the same export position skips a re-decode.
     */
    fun frameAt(uri: String, timeUs: Long): ImageBitmap? {
        if (isInvalid(uri)) return null
        val retriever = retrievers[uri] ?: return null

        val bucket = ((timeUs + FRAME_BUCKET_US / 2) / FRAME_BUCKET_US) * FRAME_BUCKET_US
        lastFrame[uri]?.let { (cachedBucket, bitmap) ->
            if (cachedBucket == bucket) return bitmap
        }

        val bitmap = try {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?.asImageBitmap()
        } catch (t: Throwable) {
            Log.w(TAG, "Frame decode failed for $uri at $timeUs us", t)
            invalid.add(uri)
            null
        } ?: return null

        lastFrame[uri] = bucket to bitmap
        return bitmap
    }

    /**
     * Open a retriever for every uri in [uris] not already open (and not
     * already known invalid), and close/drop any currently open retriever
     * whose uri fell out of the set. Re-adding a previously invalid uri after
     * it has been dropped gets a fresh open attempt.
     */
    fun bind(uris: Set<String>) {
        val toDrop = retrievers.keys.filter { it !in uris }
        toDrop.forEach { dropUri(it) }
        invalid.retainAll(uris)

        for (uri in uris) {
            if (uri in retrievers || uri in invalid) continue
            openUri(uri)
        }
    }

    /** Close every open retriever and clear all per-uri state. */
    fun release() {
        retrievers.values.forEach { closeRetriever(it) }
        retrievers.clear()
        durations.clear()
        invalid.clear()
        lastFrame.clear()
    }

    private fun openUri(uri: String) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uri))
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            retrievers[uri] = retriever
            durations[uri] = duration
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to open video uri $uri", t)
            closeRetriever(retriever)
            invalid.add(uri)
        }
    }

    private fun dropUri(uri: String) {
        retrievers.remove(uri)?.let { closeRetriever(it) }
        durations.remove(uri)
        invalid.remove(uri)
        lastFrame.remove(uri)
    }

    private fun closeRetriever(retriever: MediaMetadataRetriever) {
        runCatching { retriever.release() }
    }
}

/**
 * Binds the clips named in [uris] while [enabled] is true, and returns the
 * hub. Mirrors [rememberStudioCameraHub]: a stable [StudioVideoHub] survives
 * recomposition, re-binds whenever the requested set (or [enabled]) changes,
 * and releases every retriever when the composable leaves the composition.
 */
@Composable
fun rememberStudioVideoHub(uris: Set<String>, enabled: Boolean): StudioVideoHub {
    val context = LocalContext.current
    val hub = remember { StudioVideoHub(context) }

    LaunchedEffect(uris, enabled) {
        if (enabled) hub.bind(uris) else hub.release()
    }

    DisposableEffect(Unit) {
        onDispose { hub.release() }
    }
    return hub
}

/** ~one 15fps frame, in microseconds; the granularity [StudioVideoHub] caches decoded frames at. */
private const val FRAME_BUCKET_US = 66_000L

private const val TAG = "StudioVideoHub"
