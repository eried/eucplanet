package com.eried.eucplanet.ui.common

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Start a scan, turning Bluetooth on first if it is off.
 *
 * Every scan in the app hit the same state and handled it a different way. The
 * wheel scan showed a card with an Enable button; the radar and external GPS
 * sections put a line of grey text above their button; TPMS put one below it;
 * Flic wrote a status nobody rendered, so its button was simply dead. Same
 * situation, five answers, and three of them only tell the rider what is wrong
 * without doing anything about it.
 *
 * Telling someone their Bluetooth is off is not worth a line of the screen:
 * they came here to scan, and the useful move is to offer to turn it on. So a
 * scan asks. Android puts up its own dialog, the scan runs if the rider says
 * yes, and nothing happens at all if they say no - no message, because a
 * rider who just declined knows exactly why nothing happened.
 *
 * Usage: `val startScan = rememberScanStarter()` then
 * `onClick = { startScan { viewModel.startScan() } }`.
 */
@Composable
fun rememberScanStarter(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pending.value
        pending.value = null
        // The result code is not always RESULT_OK on every OEM even when the
        // rider agreed, so the adapter itself gets the last word.
        if (result.resultCode == Activity.RESULT_OK || bluetoothIsOn(context)) action?.invoke()
    }
    return remember(launcher, context) {
        { action: () -> Unit ->
            if (bluetoothIsOn(context)) {
                action()
            } else {
                pending.value = action
                // A phone with no Bluetooth at all, or an OEM that blocks the
                // request, throws rather than returning. Nothing to say about
                // it that the rider does not already know.
                runCatching { launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            }
        }
    }
}

private fun bluetoothIsOn(context: Context): Boolean =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
        ?.adapter?.isEnabled == true
