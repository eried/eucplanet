package com.eried.eucplanet.app

import com.eried.eucplanet.ble.BluetoothRadio
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.bluetooth_turn_on_control_center
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol

/**
 * "Enable Bluetooth" on the iPhone, Android's ACTION_REQUEST_ENABLE.
 *
 * iOS has no public link to the Bluetooth switch. Its own way is the power
 * alert: a central manager made with ShowPowerAlert while the radio is off
 * makes iOS ask "Turn On Bluetooth to Allow ..." with a Settings button that
 * goes to the Bluetooth page. iOS does not always show it (Bluetooth switched
 * off from Control Center, for one), so when the app is still in front a
 * moment later the rider is told to use Control Center instead.
 *
 * A refusal (NOT_ALLOWED) is not handled here: that one is the app's page.
 * This manager only asks; the wheel link keeps its own.
 */
internal object IosBluetoothPrompt {
    private var manager: CBCentralManager? = null
    private val delegate = Delegate()
    private val scope = MainScope()

    /** Call on the main thread, with the radio off. */
    fun askToTurnOn() {
        var alertShown = false
        val center = NSNotificationCenter.defaultCenter
        val observer: NSObjectProtocol = center.addObserverForName(
            UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue,
        ) { _ -> alertShown = true }
        // A fresh manager each time: iOS raises the alert when one is made.
        manager = CBCentralManager(
            delegate = delegate,
            queue = null,
            options = mapOf<Any?, Any?>(CBCentralManagerOptionShowPowerAlertKey to true),
        )
        scope.launch {
            delay(ALERT_WAIT_MS)
            center.removeObserver(observer)
            if (!alertShown && IosAppGraph.link.radio.value == BluetoothRadio.OFF) {
                IosAppGraph.notices.post(getString(Res.string.bluetooth_turn_on_control_center))
            }
        }
    }

    /** How long iOS gets to put its alert up before the snackbar says it instead. */
    private const val ALERT_WAIT_MS = 1500L

    private class Delegate : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) = Unit
    }
}
