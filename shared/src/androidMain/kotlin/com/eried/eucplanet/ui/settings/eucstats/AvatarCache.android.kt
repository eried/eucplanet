package com.eried.eucplanet.ui.settings.eucstats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * The pictures are shown in a 40 to 48 dp circle, so they are downsampled to
 * [MAX_AVATAR_PX] on the way in: an account that uploaded a 2000 px portrait
 * costs the same as everyone else instead of holding 16 MB. Sized in bytes
 * rather than entries, which is about 30 avatars at the decode cap and many
 * more at the usual 64 px, and the least recently drawn one goes first.
 */
actual object AvatarCache {
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

    actual fun peek(url: String): ImageBitmap? = cache.get(url)?.asImageBitmap()

    /** Fetches under way, so two misses on the same URL share one download. */
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    actual suspend fun load(url: String): ImageBitmap? = loadBitmap(url)?.asImageBitmap()

    private suspend fun loadBitmap(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        val job = inFlight.computeIfAbsent(url) {
            scope.async {
                try {
                    runCatching { download(url)?.let(::decodeScaled) }
                        .getOrNull()
                        ?.also { cache.put(url, it) }
                } finally {
                    inFlight.remove(url)
                }
            }
        }
        return job.await()
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

actual fun isoCountryCodes(): List<String> = Locale.getISOCountries().toList()

actual fun platformCountryName(code: String): String = Locale("", code).displayCountry
