package com.eried.eucplanet.ui.scan

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The Android [ScanHost], over the Activity the scan screen sits in. */
@Composable
fun rememberAndroidScanHost(): ScanHost {
    val context = LocalContext.current
    return remember(context) { AndroidScanHost(context) }
}

private class AndroidScanHost(private val context: Context) : ScanHost {

    @Composable
    override fun rememberPermissionRequest(onResult: () -> Unit): (List<String>) -> Unit {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { onResult() }
        return { permissions -> launcher.launch(permissions.toTypedArray()) }
    }

    @Composable
    override fun rememberEnableBluetooth(onResult: () -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { onResult() }
        return { launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
    }

    override fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
