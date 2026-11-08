package com.eried.eucplanet.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberShortTimeFormatter(): (Long) -> String {
    val ctx = LocalContext.current
    return remember(ctx) {
        { ms -> android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(ms)) }
    }
}

@Composable
actual fun rememberClockAxisFormatter(): (Long) -> String {
    val ctx = LocalContext.current
    val is24h = remember(ctx) { android.text.format.DateFormat.is24HourFormat(ctx) }
    val clockFmt = remember(is24h) {
        java.text.SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", java.util.Locale.getDefault())
    }
    return remember(clockFmt) { { ms -> clockFmt.format(java.util.Date(ms)) } }
}
