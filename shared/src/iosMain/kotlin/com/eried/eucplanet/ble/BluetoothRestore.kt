package com.eried.eucplanet.ble

import com.eried.eucplanet.service.RestoredPeripheral
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralStateConnected

/**
 * CoreBluetooth state restoration. Each central that holds a link (the
 * wheel, the radar, the GPS box) is created with its own restore id, so
 * when iOS ends the app in the background to free memory it keeps those
 * links, and starts the app again, with no screen, when one of them talks.
 * The relaunched app makes the same centrals at launch (IosAppStart), and
 * iOS hands each its peripherals back in willRestoreState. The ids must
 * never change: a new id is a central iOS knows nothing about.
 *
 * The scans (the pairing screens, the tyre caps) have no restore id: they
 * only run with the app in front.
 */
object BluetoothRestore {
    const val WHEEL = "com.eried.eucplanet.central.wheel"
    const val RADAR = "com.eried.eucplanet.central.radar"
    const val EXTERNAL_GPS = "com.eried.eucplanet.central.externalgps"
    const val ENGO = "com.eried.eucplanet.central.engo"

    /** The options for a central restored under [id]. */
    fun options(id: String): Map<Any?, Any?> = mapOf(CBCentralManagerOptionRestoreIdentifierKey to id)

    /** The peripherals in a willRestoreState dictionary. */
    fun peripherals(state: Map<Any?, *>): List<CBPeripheral> =
        (state[CBCentralManagerRestoredStatePeripheralsKey] as? List<*>).orEmpty().mapNotNull { it as? CBPeripheral }

    fun describe(p: CBPeripheral): RestoredPeripheral =
        RestoredPeripheral(p.identifier.UUIDString, p.name, connected = p.state == CBPeripheralStateConnected)
}
