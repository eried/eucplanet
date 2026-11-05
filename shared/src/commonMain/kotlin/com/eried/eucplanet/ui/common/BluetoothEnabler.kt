package com.eried.eucplanet.ui.common

import androidx.compose.runtime.Composable

/**
 * Start a scan, turning Bluetooth on first if it is off: Android asks with
 * its own dialog (BluetoothEnabler.android.kt explains why every scan does
 * this). iOS has no such dialog; CoreBluetooth prompts on its own, so the
 * scan just starts.
 *
 * Usage: `val startScan = rememberScanStarter()` then
 * `onClick = { startScan { viewModel.startScan() } }`.
 */
@Composable
expect fun rememberScanStarter(): (() -> Unit) -> Unit
