package com.eried.eucplanet.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The tour's clips are Android assets, not in the iOS bundle yet, so the
 * step keeps the clip's space and shows its text alone.
 */
@Composable
internal actual fun LoopingClip(assetName: String, modifier: Modifier) {
    Box(modifier)
}
