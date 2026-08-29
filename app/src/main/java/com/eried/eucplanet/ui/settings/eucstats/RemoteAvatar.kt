package com.eried.eucplanet.ui.settings.eucstats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads a small remote avatar (the eucstats `avatar_url`) without an image
 * library. Avatars are tiny (the server re-encodes to ~64 px), so a plain
 * decode on IO is plenty. While loading, on a blank URL, or on any failure,
 * [fallback] is shown, so callers keep their initials / placeholder until
 * (and unless) the real photo arrives. Re-fetches whenever [url] changes.
 *
 * Decoded bitmaps live in a process-wide [AvatarCache], so re-opening the
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
    val bitmap by produceState(initialValue = cached, url) {
        value = cached ?: url?.takeIf { it.isNotBlank() }?.let { AvatarCache.load(it) }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = remember(bmp) { bmp.asImageBitmap() },
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        fallback()
    }
}

/**
 * The avatars this process has already decoded, keyed by URL.
 *
 * The pictures are shown in a 40 to 48 dp circle, so they are downsampled to
 * [MAX_AVATAR_PX] on the way in: an account that uploaded a 2000 px portrait
 * costs the same as everyone else instead of holding 16 MB. Sized in bytes
 * rather than entries, which is about 30 avatars at the decode cap and many
 * more at the usual 64 px, and the least recently drawn one goes first.
 */
private object AvatarCache {
    /** The longest edge kept after decoding, comfortably over a 48 dp circle
     *  at 3x density. */
    private const val MAX_AVATAR_PX = 192

    /** Nothing legitimate is bigger than this, and a stray URL that points at
     *  something huge must not be pulled into memory to find that out. */
    private const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024

    private const val CACHE_BYTES = 4 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** What is already decoded, for a caller that must not block. */
    fun peek(url: String): Bitmap? = cache.get(url)

    /** The cached bitmap, or one fetch and decode on IO that fills the cache. */
    suspend fun load(url: String): Bitmap? =
        cache.get(url) ?: withContext(Dispatchers.IO) {
            runCatching { download(url)?.let(::decodeScaled) }
                .getOrNull()
                ?.also { cache.put(url, it) }
        }

    private fun download(url: String): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        return try {
            conn.inputStream.use { it.readAtMost(MAX_DOWNLOAD_BYTES) }
        } finally {
            conn.disconnect()
        }
    }

    /** Reads the whole body, or gives up as soon as it is clearly not an
     *  avatar. Returns null past the cap rather than a truncated image. */
    private fun InputStream.readAtMost(limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > limit) return null
            out.write(buf, 0, n)
        }
    }

    /** Decodes at the smallest power-of-two sample that still covers the
     *  circle on screen, so a big portrait is never held at full resolution. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= MAX_AVATAR_PX) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}
