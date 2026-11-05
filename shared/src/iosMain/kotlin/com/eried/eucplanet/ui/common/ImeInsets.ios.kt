package com.eried.eucplanet.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density

@Composable
actual fun imeTargetBottom(density: Density): Int = WindowInsets.ime.getBottom(density)
