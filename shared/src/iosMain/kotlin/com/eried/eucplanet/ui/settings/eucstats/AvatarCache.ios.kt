package com.eried.eucplanet.ui.settings.eucstats

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.eried.eucplanet.platform.HttpClient
import com.eried.eucplanet.platform.HttpRequest
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.currentLocale
import platform.Foundation.ISOCountryCodes

/**
 * The iPhone's avatar cache: the same size caps as Android's, decoded with
 * Skia, at most [MAX_ENTRIES] kept (the oldest goes first).
 */
actual object AvatarCache {
    private const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024
    private const val MAX_ENTRIES = 64

    private val lock = SynchronizedObject()
    private val cache = LinkedHashMap<String, ImageBitmap>()
    private val http by lazy { HttpClient(connectTimeoutMs = 8000, readTimeoutMs = 8000) }

    actual fun peek(url: String): ImageBitmap? = synchronized(lock) { cache[url] }

    actual suspend fun load(url: String): ImageBitmap? {
        peek(url)?.let { return it }
        val image = withContext(Dispatchers.IO) {
            runCatching {
                val resp = http.execute(HttpRequest(url, HttpRequest.Body.None))
                if (!resp.isSuccessful || resp.body.size > MAX_DOWNLOAD_BYTES) null
                else org.jetbrains.skia.Image.makeFromEncoded(resp.body).toComposeImageBitmap()
            }.getOrNull()
        } ?: return null
        synchronized(lock) {
            cache[url] = image
            while (cache.size > MAX_ENTRIES) cache.remove(cache.keys.first())
        }
        return image
    }
}

actual fun isoCountryCodes(): List<String> = NSLocale.ISOCountryCodes.filterIsInstance<String>()

actual fun platformCountryName(code: String): String =
    NSLocale.currentLocale.displayNameForKey(NSLocaleCountryCode, code).orEmpty()
