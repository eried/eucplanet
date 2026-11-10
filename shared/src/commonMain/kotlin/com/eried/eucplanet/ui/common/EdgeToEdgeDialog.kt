package com.eried.eucplanet.ui.common

import androidx.compose.ui.window.DialogProperties

/**
 * A full-screen dialog that draws under the system bars and pads itself with
 * the window insets. Android also has to be told not to fit the decor to the
 * system windows, a flag the common DialogProperties does not carry.
 */
expect fun edgeToEdgeDialogProperties(): DialogProperties
