package com.eried.eucplanet.ui.dashboard

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer

@Composable
internal actual fun LoopingClip(assetName: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                runCatching {
                    val bytes = ctx.assets.open(assetName).use { it.readBytes() }
                    val drawable = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                    )
                    setImageDrawable(drawable)
                    (drawable as? AnimatedImageDrawable)?.apply {
                        repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        start()
                    }
                }
            }
        }
    )
}
