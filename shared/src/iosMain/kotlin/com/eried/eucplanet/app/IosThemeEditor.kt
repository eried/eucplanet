package com.eried.eucplanet.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.eried.eucplanet.ui.theme.ThemeEditorSurface
import com.eried.eucplanet.ui.theme.ThemeEditorViewModel
import com.eried.eucplanet.ui.theme.ThemeEditorWidget
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The floating theme editor on iOS, mounted over every screen as MainActivity
 * mounts it on Android. The color identifier reads its pixel from a layer the
 * app's screens are recorded into while the editor is open (Android uses
 * PixelCopy on its window).
 */
internal object IosThemeEditor {
    private val _enabled = MutableStateFlow(false)

    /** Settings -> Display -> Theme editor, once the launch reset has run. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun start() {
        MainScope().launch {
            // Session only, as MainActivity: a fresh start clears a persisted "on".
            if (IosAppGraph.settings.get().themeEditorEnabled) IosAppGraph.theme.setEditorEnabled(false)
            IosAppGraph.settings.settings
                .map { it.themeEditorEnabled }
                .distinctUntilChanged()
                .collect { _enabled.value = it }
        }
    }

    /** The app's screens, with the editor floating over them when it is on. */
    @Composable
    fun Root(content: @Composable () -> Unit) {
        val on by enabled.collectAsState()
        val layer = rememberGraphicsLayer()
        val surface = remember(layer) { LayerSurface(layer) }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { surface.size = it }
                    .then(
                        if (on) Modifier.drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        } else Modifier
                    )
            ) { content() }
            if (on) {
                val vm = rememberViewModel { ThemeEditorViewModel(IosAppGraph.settings, IosAppGraph.theme) }
                ThemeEditorWidget(vm = vm, surface = surface)
            }
        }
    }

    /** The screens' size, and a pixel of their last frame. */
    private class LayerSurface(private val layer: GraphicsLayer) : ThemeEditorSurface {
        var size = IntSize.Zero
        override val width: Int get() = size.width
        override val height: Int get() = size.height

        override fun samplePixel(x: Int, y: Int, onColor: (Color) -> Unit) {
            MainScope().launch {
                val argb = runCatching {
                    val image = layer.toImageBitmap()
                    if (x >= image.width || y >= image.height) null
                    else IntArray(1).also { image.readPixels(it, x, y, 1, 1) }[0]
                }.getOrNull() ?: return@launch
                onColor(Color(argb))
            }
        }
    }
}
