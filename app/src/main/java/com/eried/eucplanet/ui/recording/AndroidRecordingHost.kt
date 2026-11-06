package com.eried.eucplanet.ui.recording

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** The trip list's Activity hooks: the back gesture, the location prompt and the document picker. */
@Composable
fun rememberAndroidRecordingHost(): RecordingHost = remember { AndroidRecordingHost }

private object AndroidRecordingHost : RecordingHost {
    override val supportsTripTools = true

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
        androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
    }

    @Composable
    override fun rememberLocationPermissionRequest(onResult: () -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { onResult() }
        return {
            launcher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    @Composable
    override fun rememberTripImportPicker(onPicked: (String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) onPicked(uri.toString())
        }
        return {
            launcher.launch(arrayOf(
                "application/octet-stream",
                "application/zip",
                "text/csv",
                "text/comma-separated-values",
                "*/*"
            ))
        }
    }
}
