package com.eried.eucplanet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.eried.eucplanet.ui.charging.ChargingCsvFiles
import com.eried.eucplanet.ui.charging.ChargingHost
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController

/**
 * The charging monitor's Export on iOS: no save dialog comes first, the
 * file name is the handle, and the written CSV opens in the share sheet
 * (Save to Files, AirDrop, mail), as the trip export does.
 */
internal object IosChargingHost : ChargingHost {
    @Composable
    override fun rememberCsvSaver(onPicked: (String) -> Unit): (String) -> Unit {
        val picked = rememberUpdatedState(onPicked)
        return remember { { name -> picked.value(name) } }
    }
}

internal object IosChargingCsvFiles : ChargingCsvFiles {
    @OptIn(ExperimentalForeignApi::class)
    override fun write(target: String, bytes: ByteArray) {
        val path = NSTemporaryDirectory() + target
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        check(data.writeToFile(path, atomically = true)) { "could not write $path" }
        MainScope().launch {
            val top = topViewController() ?: return@launch
            val sheet = UIActivityViewController(
                activityItems = listOf(NSURL.fileURLWithPath(path)),
                applicationActivities = null,
            )
            top.presentViewController(sheet, animated = true, completion = null)
        }
    }
}
