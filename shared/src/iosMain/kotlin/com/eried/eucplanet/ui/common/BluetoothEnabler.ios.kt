package com.eried.eucplanet.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberScanStarter(): (() -> Unit) -> Unit = remember { { action: () -> Unit -> action() } }
