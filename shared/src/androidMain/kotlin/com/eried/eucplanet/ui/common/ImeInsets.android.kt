package com.eried.eucplanet.ui.common

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density

@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun imeTargetBottom(density: Density): Int = WindowInsets.imeAnimationTarget.getBottom(density)
