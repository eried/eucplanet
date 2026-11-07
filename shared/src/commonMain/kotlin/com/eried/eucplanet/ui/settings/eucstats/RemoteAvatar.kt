package com.eried.eucplanet.ui.settings.eucstats

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * Loads a small remote avatar (the eucstats `avatar_url`) without an image
 * library. Avatars are tiny (the server re-encodes to ~64 px), so a plain
 * decode on IO is plenty. While loading, on a blank URL, or on any failure,
 * [fallback] is shown, so callers keep their initials / placeholder until
 * (and unless) the real photo arrives. Re-fetches whenever [url] changes.
 *
 * Decoded pictures live in a process-wide [AvatarCache], so re-opening the
 * settings card or the live share group does not pull every picture down
 * again while the rider is out riding.
 */
@Composable
fun RemoteAvatar(
    url: String?,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    // Straight from the cache on the very first frame when it is already
    // there, so a re-opened dialog draws the photo instead of flashing the
    // initial for a frame.
    val cached = remember(url) { url?.takeIf { it.isNotBlank() }?.let { AvatarCache.peek(it) } }
    val bitmap by produceState<ImageBitmap?>(initialValue = cached, url) {
        value = cached ?: url?.takeIf { it.isNotBlank() }?.let { AvatarCache.load(it) }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        fallback()
    }
}

/**
 * The avatars this process has already decoded, keyed by URL, downsampled
 * on the way in. Android decodes with BitmapFactory into an LruCache sized in
 * bytes; iOS with Skia.
 */
expect object AvatarCache {
    /** What is already decoded, for a caller that must not block. */
    fun peek(url: String): ImageBitmap?

    /** The cached picture, or one fetch and decode on IO that fills the cache. */
    suspend fun load(url: String): ImageBitmap?
}

/** Every ISO 3166-1 alpha-2 code the platform knows (`Locale.getISOCountries()`). */
expect fun isoCountryCodes(): List<String>

/** Localised country name for a 2-letter code, or "" when the platform has none. */
expect fun platformCountryName(code: String): String
