package com.eried.eucplanet.ui.settings.eucstats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads a small remote avatar (the eucstats `avatar_url`) without an image
 * library. Avatars are tiny (the server re-encodes to ~64 px), so a plain
 * decode on IO is plenty. While loading, on a blank URL, or on any failure,
 * [fallback] is shown — so callers keep their initials / placeholder until
 * (and unless) the real photo arrives. Re-fetches whenever [url] changes.
 */
@Composable
fun RemoteAvatar(
    url: String?,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, url) {
        value = if (url.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
        }
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
