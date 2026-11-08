package com.eried.eucplanet.ui.charging

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** The CSV goes to the document the system save dialog picked. */
class AndroidChargingCsvFiles(private val context: Context) : ChargingCsvFiles {
    override fun write(target: String, bytes: ByteArray) {
        context.contentResolver.openOutputStream(Uri.parse(target))?.use { it.write(bytes) }
    }
}

/** Android's [ChargingHost]: the system save dialog for the Export item. */
@Composable
fun rememberAndroidChargingHost(): ChargingHost = remember { AndroidChargingHost }

private object AndroidChargingHost : ChargingHost {
    @Composable
    override fun rememberCsvSaver(onPicked: (String) -> Unit): (String) -> Unit {
        // The system save dialog picks the destination; the view model streams
        // the CSV in off the main thread.
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv")
        ) { uri -> uri?.let { onPicked(it.toString()) } }
        return { name -> launcher.launch(name) }
    }
}
