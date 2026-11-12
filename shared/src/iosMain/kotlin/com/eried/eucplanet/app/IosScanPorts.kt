package com.eried.eucplanet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.eried.eucplanet.ble.BleDevice
import com.eried.eucplanet.ble.BluetoothRadio
import com.eried.eucplanet.ble.IosWheelLink
import com.eried.eucplanet.ble.isLikelyWheelName
import com.eried.eucplanet.ui.scan.ScanHost
import com.eried.eucplanet.ui.scan.ScanPlatform
import com.eried.eucplanet.ui.scan.WheelScanner
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/** CoreBluetooth's scan through IosWheelLink, filtered like Android's BleScanner. */
internal class IosWheelScanner(private val link: IosWheelLink) : WheelScanner {
    override fun scanForDevices(showAll: Boolean): Flow<BleDevice> = callbackFlow {
        link.startScan()
        val job = launch {
            link.scanResults.collect { list ->
                list.forEach { w ->
                    val name = w.name ?: return@forEach
                    if (showAll || isLikelyWheelName(name)) {
                        trySend(BleDevice(name = name, address = w.address, rssi = w.rssi))
                    }
                }
            }
        }
        awaitClose {
            job.cancel()
            link.stopScan()
        }
    }

    override fun isBluetoothEnabled(): Boolean = link.bluetoothOn.value
    override fun stopScan() = link.stopScan()
}

internal class IosScanPlatform(private val link: IosWheelLink) : ScanPlatform {
    // CoreBluetooth asks for its permission itself on first use.
    override fun missingScanPermissions(): List<String> = emptyList()

    override fun watchBluetooth(listener: (Boolean) -> Unit): () -> Unit {
        val scope = MainScope()
        // Only changes, as Android's broadcast reports them.
        scope.launch { link.bluetoothOn.drop(1).collect { listener(it) } }
        return { scope.cancel() }
    }

    override fun mayConnect(): Boolean = true

    override fun connect(address: String, name: String) {
        IosAppGraph.wheel.connect(address, name)
    }
}

/**
 * No permission dialog to raise on iOS. "Enable Bluetooth" raises iOS's own
 * turn-on alert while the radio is off, and opens the app's page in Settings
 * when Bluetooth is not allowed, since switching the radio on would not help.
 */
internal object IosScanHost : ScanHost {
    @Composable
    override fun rememberPermissionRequest(onResult: () -> Unit): (List<String>) -> Unit =
        remember { { _ -> onResult() } }

    @Composable
    override fun rememberEnableBluetooth(onResult: () -> Unit): () -> Unit =
        remember {
            {
                if (IosAppGraph.link.radio.value == BluetoothRadio.NOT_ALLOWED) openAppSettings()
                else IosBluetoothPrompt.askToTurnOn()
                onResult()
            }
        }

    override fun openAppSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>(), null)
    }
}
