package com.eried.eucplanet.wear.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.eried.eucplanet.wear.ui.utils.LocalWatchColors
import kotlinx.coroutines.delay

/**
 * The watch's counterpart of the phone's AppNotifier: plain (non-composable)
 * code posts a short message and [WatchSnackbarHost] shows it at the app root.
 * Wear Compose Material has no Snackbar and the OS Toast ignores the app
 * theme, so the host draws its own pill in the watch palette: no icon, a line
 * or two of text, gone after a couple of seconds. A new message replaces the
 * one still showing instead of queueing behind it.
 */
internal object WatchNotifier {
    class Message(val text: String, val id: Long)

    var current: Message? by mutableStateOf(null)
        private set
    private var seq = 0L

    fun post(text: String) {
        current = Message(text, ++seq)
    }

    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }
}

private const val SHOW_MS = 2000L

/** Draws the pending [WatchNotifier] message; place it once at the app root. */
@Composable
internal fun WatchSnackbarHost(modifier: Modifier = Modifier) {
    val colors = LocalWatchColors.current
    val message = WatchNotifier.current
    // Keep the last text through the exit fade so the pill does not blank
    // before it disappears.
    var shown by remember { mutableStateOf("") }
    if (message != null) shown = message.text
    LaunchedEffect(message?.id) {
        val id = message?.id ?: return@LaunchedEffect
        delay(SHOW_MS)
        WatchNotifier.dismiss(id)
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = shown,
                color = colors.textPrimary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}
