@file:OptIn(ExperimentalForeignApi::class)

package com.eried.eucplanet.tpms

import com.eried.eucplanet.ble.cbFullUuidString
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.NSObject

/**
 * The iOS side of [TpmsRadio]: a CoreBluetooth scan of its own for the tyre
 * monitor. The rules (when to search, when to watch, what to adopt) are the
 * shared [TpmsScanner]'s, as on Android.
 *
 * Where CoreBluetooth differs from AndroidTpmsRadio:
 *  - The address is the cap's MAC read from its payload
 *    ([TpmsPayloadAddress]), since iOS never shows a MAC; anything else keeps
 *    the peripheral's per-phone UUID. Without it the LY family, found by its
 *    payload ending in its own MAC backwards, could never be identified or
 *    decoded here. The price is that this check passes by construction on
 *    iOS, so an LY cap is told apart by company id and exact length alone.
 *  - CoreBluetooth cannot filter a scan by address, so [startScan]'s
 *    addresses are applied here, to what was heard.
 *  - There is no scan mode: the balanced monitor and the full-duty search are
 *    the same scan. Duplicates are allowed, or iOS reports each cap once and
 *    every later reading is lost.
 *  - The caps advertise no service to filter on, and iOS delivers nothing to
 *    an unfiltered scan in the background (and ignores allow-duplicates
 *    there), so the pressure moves only while the app is in front. The scan
 *    is reopened on every return to the front.
 */
class IosTpmsRadio : TpmsRadio {

    private val open = TpmsListeners()
    private val watchers = ArrayList<(Boolean) -> Unit>()
    private var wasOn: Boolean? = null

    private val delegate = Delegate()
    private val central = CBCentralManager(delegate, null)

    init {
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue,
        ) { _ -> if (!open.isEmpty() && isOn()) openScan() }
    }

    override fun isOn(): Boolean = central.state == CBManagerStatePoweredOn

    override fun startScan(listener: TpmsRadio.Listener, lowPower: Boolean, addresses: List<String>?) {
        if (!isOn()) throw IllegalStateException("Bluetooth not available")
        open.add(listener, addresses)
        openScan()
    }

    override fun stopScan(listener: TpmsRadio.Listener) {
        if (open.remove(listener) && open.isEmpty()) central.stopScan()
    }

    override fun watchBluetooth(onChange: (Boolean) -> Unit) {
        watchers += onChange
    }

    private fun openScan() {
        central.stopScan()
        central.scanForPeripheralsWithServices(
            null,
            mapOf<Any?, Any?>(CBCentralManagerScanOptionAllowDuplicatesKey to true),
        )
    }

    private fun advertOf(p: CBPeripheral, data: Map<Any?, *>, rssi: Int): TpmsAdvert {
        val serviceData = ArrayList<Pair<String, ByteArray>>()
        (data[CBAdvertisementDataServiceDataKey] as? Map<*, *>)?.forEach { (k, v) ->
            val uuid = k as? CBUUID ?: return@forEach
            val bytes = (v as? NSData)?.toByteArray() ?: return@forEach
            serviceData += uuid.cbFullUuidString() to bytes
        }
        return tpmsAdvertOf(
            peripheralId = p.identifier.UUIDString,
            name = data[CBAdvertisementDataLocalNameKey] as? String ?: p.name,
            rssi = rssi,
            manufacturerData = (data[CBAdvertisementDataManufacturerDataKey] as? NSData)?.toByteArray(),
            serviceData = serviceData,
            serviceUuids = (data[CBAdvertisementDataServiceUUIDsKey] as? List<*>)
                ?.mapNotNull { (it as? CBUUID)?.cbFullUuidString() }.orEmpty(),
        )
    }

    private inner class Delegate : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            val on = isOn()
            if (on == wasOn) return
            // The first report (unknown to on) counts too: the monitor tried
            // before CoreBluetooth had said anything and needs the nudge.
            wasOn = on
            Log.i(TAG, "Bluetooth " + if (on) "on" else "off")
            watchers.toList().forEach { it(on) }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            if (open.isEmpty()) return
            open.hear(advertOf(didDiscoverPeripheral, advertisementData, RSSI.intValue))
        }
    }

    private companion object {
        const val TAG = "IosTpmsRadio"
    }
}

/**
 * One advert in [TpmsScanner]'s terms, from what CoreBluetooth reports.
 * [manufacturerData] is CoreBluetooth's blob, company id in front (little
 * endian); Android hands the id over apart, so it is split off the same way.
 * The address is the MAC the payload carries ([TpmsPayloadAddress]), else
 * [peripheralId].
 */
internal fun tpmsAdvertOf(
    peripheralId: String,
    name: String?,
    rssi: Int,
    manufacturerData: ByteArray?,
    serviceData: List<Pair<String, ByteArray>> = emptyList(),
    serviceUuids: List<String> = emptyList(),
): TpmsAdvert {
    val manufacturer = ArrayList<Pair<Int, ByteArray>>()
    if (manufacturerData != null && manufacturerData.size >= 2) {
        val id = (manufacturerData[0].toInt() and 0xFF) or ((manufacturerData[1].toInt() and 0xFF) shl 8)
        manufacturer += id to manufacturerData.copyOfRange(2, manufacturerData.size)
    }
    return TpmsAdvert(
        address = TpmsPayloadAddress.of(manufacturer) ?: peripheralId,
        name = name,
        rssi = rssi,
        manufacturer = manufacturer,
        serviceData = serviceData,
        serviceUuids = serviceUuids,
    )
}

/**
 * The open scans and their address filters. CoreBluetooth cannot filter by
 * address, so each advert reaches only the listeners whose filter lets it
 * through (null: hear everything), as Android's ScanFilter would.
 */
internal class TpmsListeners {
    private val open = LinkedHashMap<TpmsRadio.Listener, List<String>?>()

    fun add(listener: TpmsRadio.Listener, addresses: List<String>?) {
        open[listener] = addresses?.toList()
    }

    /** True when [listener] had a scan open. */
    fun remove(listener: TpmsRadio.Listener): Boolean {
        if (!open.containsKey(listener)) return false
        open.remove(listener)
        return true
    }

    fun isEmpty(): Boolean = open.isEmpty()

    fun hear(advert: TpmsAdvert) {
        for ((listener, filter) in open.entries.toList()) {
            if (TpmsPayloadAddress.passes(advert.address, filter)) listener.onAdvert(advert)
        }
    }
}
