package com.eried.eucplanet.ui.theme

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView

/** The theme editor's window on Android: the compose view and PixelCopy. */
class AndroidThemeEditorSurface(private val view: View) : ThemeEditorSurface {
    override val width: Int get() = view.width
    override val height: Int get() = view.height

    override fun samplePixel(x: Int, y: Int, onColor: (Color) -> Unit) {
        val window = (view.context as? Activity)?.window ?: return
        // Copy ONLY a 1x1 region around the crosshair -- never the whole window.
        // A full-screen ARGB_8888 bitmap is ~18 MB on a 1440p phone, and
        // allocating + PixelCopy-ing that synchronously on the main thread on
        // every Identify froze low-RAM / large-screen devices. One pixel is all
        // we need to match a token. (Window+Rect overload is API 26+; minSdk 29.)
        val src = Rect(x, y, x + 1, y + 1)
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(window, src, bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    onColor(Color(bmp.getPixel(0, 0)))
                }
                bmp.recycle()
            }, Handler(Looper.getMainLooper()))
        }.onFailure { bmp.recycle() }
    }
}

/** The surface of the view this composition draws in. */
@Composable
fun rememberAndroidThemeEditorSurface(): ThemeEditorSurface {
    val view = LocalView.current
    return remember(view) { AndroidThemeEditorSurface(view) }
}
