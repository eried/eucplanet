package com.eried.eucplanet.ui.scan

import androidx.compose.runtime.Composable

/** What ScanScreen asks of the platform: the system prompts it opens. */
interface ScanHost {
    /** A permission request; the returned function asks for the given ones. */
    @Composable
    fun rememberPermissionRequest(onResult: () -> Unit): (List<String>) -> Unit

    /** The system "turn Bluetooth on" prompt; the returned function shows it. */
    @Composable
    fun rememberEnableBluetooth(onResult: () -> Unit): () -> Unit

    /** This app's page in the system settings. */
    fun openAppSettings()
}
