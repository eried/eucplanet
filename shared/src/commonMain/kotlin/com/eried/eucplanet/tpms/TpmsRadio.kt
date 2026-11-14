package com.eried.eucplanet.tpms

/**
 * One advertisement as the radio heard it, before anything decides what it is.
 *
 * Everything [TpmsScanner] reads off a scan result, in plain types so the
 * scanner's rules run on any phone.
 */
class TpmsAdvert(
    val address: String,
    /** The advertised name, or the name the phone already knows the device by. */
    val name: String?,
    val rssi: Int,
    /**
     * Manufacturer id and payload, company-id bytes already stripped, in the
     * order the radio lists them (by id on Android).
     */
    val manufacturer: List<Pair<Int, ByteArray>>,
    /** Service UUID text and its payload. */
    val serviceData: List<Pair<String, ByteArray>>,
    /** Advertised service UUIDs, which carry no payload of their own. */
    val serviceUuids: List<String>,
)

/**
 * The phone's Bluetooth scan, as the tyre monitor needs it. Android:
 * AndroidTpmsRadio in :app. iOS: not yet.
 */
interface TpmsRadio {

    /** Hears what one open scan picks up. */
    interface Listener {
        fun onAdvert(advert: TpmsAdvert)

        /** The platform ended the scan by itself with [errorCode]. */
        fun onFailed(errorCode: Int)
    }

    /** True when a scan could open now: there is a radio and it is on. */
    fun isOn(): Boolean

    /**
     * Open a scan that reports to [listener] until [stopScan].
     *
     * [lowPower] is the background monitor (a balanced mode that still
     * catches a cap's short burst); false is the rider's search, at full
     * duty. [addresses] limits the scan to those devices; when null, or when
     * the platform refuses one of them, the scan hears everything. Throws when
     * the scan cannot start.
     */
    fun startScan(listener: Listener, lowPower: Boolean, addresses: List<String>?)

    /** Close the scan [listener] was opened with. */
    fun stopScan(listener: Listener)

    /** Calls [onChange] with false when Bluetooth turns (or is turning) off, true when it is back on. */
    fun watchBluetooth(onChange: (Boolean) -> Unit)
}
