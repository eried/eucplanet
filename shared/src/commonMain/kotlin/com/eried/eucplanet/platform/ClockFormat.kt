package com.eried.eucplanet.platform

import androidx.compose.runtime.Composable

/**
 * The phone's own short time for an epoch-ms instant ("14:05" or "2:05 PM"),
 * as Android's `DateFormat.getTimeFormat(context)` writes it.
 */
@Composable
expect fun rememberShortTimeFormatter(): (Long) -> String

/**
 * "HH:mm" or "h:mm a" in the phone's language, following its 24-hour
 * setting, for chart time axes. One formatter per composition, not per label.
 */
@Composable
expect fun rememberClockAxisFormatter(): (Long) -> String
